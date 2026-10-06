package io.meridian.reconciliation.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.reconciliation.domain.CamtStatementParser;
import io.meridian.reconciliation.domain.CsvStatementParser;
import io.meridian.reconciliation.domain.ParsedStatement;
import io.meridian.reconciliation.domain.StatementLine;
import io.meridian.reconciliation.infrastructure.FileStore;
import io.meridian.reconciliation.infrastructure.ReconStore;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Statement / settlement file intake. A file arrives with its {@code content} (CSV, camt.053/054 XML or a JSON
 * array, per the source's file_format) or as pre-parsed {@code records}; it is stored, de-duplicated by SHA-256,
 * parsed into immutable recon_records and checked (currency, opening + movements = closing). A file that fails to
 * parse is kept as FAILED and can be reprocessed after the source's parser_config is fixed.
 */
@Service
public class FileService {
    private static final String RECORDS_SUFFIX = "/records.json";

    private final ReconStore store;
    private final CrudEngine crud;
    private final FileStore files;
    private final OutboxWriter outbox;
    private final JsonMapper json;

    public FileService(ReconStore store, CrudEngine crud, FileStore files, OutboxWriter outbox, JsonMapper json) {
        this.store = store;
        this.crud = crud;
        this.files = files;
        this.outbox = outbox;
        this.json = json;
    }

    @Transactional
    public String create(JsonNode req) {
        UUID sourceId = Fields.requireUuid(req, "recon_source_id");
        Fields.requireText(req, "file_name");
        JsonNode source = store.find("recon_source", sourceId).orElseThrow(() ->
                ApiException.unprocessable("source_not_found", "Reconciliation source " + sourceId + " does not exist"));
        if (!source.get("is_active").asBoolean()) {
            throw ApiException.unprocessable("source_inactive", "Reconciliation source " + sourceId + " is not active");
        }
        String content = Fields.text(req, "content");
        JsonNode records = req.path("records");
        boolean preParsed = content == null && records.isArray();
        if (content == null && !preParsed) {
            throw ApiException.unprocessable("content_required",
                    "Send the file in 'content' (text) or the parsed lines in 'records' (array)");
        }
        byte[] bytes = (preParsed ? json.writeValueAsString(records) : content).getBytes(StandardCharsets.UTF_8);
        String checksum = sha256(bytes);
        store.advisoryLock("recon_file:" + TenantContext.require() + ":" + checksum);
        store.row("select row_to_json(t)::text from recon_file t where t.checksum = ?", checksum).ifPresent(f -> {
            throw ApiException.conflict("duplicate_file", "The same file was already received as " + f.get("id").asString());
        });
        UUID id = Ids.newId();
        String key = "recon-files/" + TenantContext.require() + "/" + id
                + (preParsed ? RECORDS_SUFFIX : "/" + Fields.requireText(req, "file_name").replaceAll("[^A-Za-z0-9._-]", "_"));
        ObjectNode v = crud.toColumns("recon_file", req);
        v.remove("record_count");
        v.remove("error");
        v.put("id", id.toString());
        v.put("file_uri", files.put(key, bytes));
        v.put("checksum", checksum);
        v.put("status", "RECEIVED");
        JsonNode file = json.readTree(crud.insert("recon_file", v));
        return parseAndStore(file, source, bytes, preParsed);
    }

    /** Parses a FAILED file again with the source's current parser configuration. */
    @Transactional
    public String reprocess(UUID id) {
        JsonNode file = store.requireLocked("recon_file", id);
        String status = file.get("status").asString();
        if (!"FAILED".equals(status)) {
            throw ApiException.conflict("invalid_transition", "Only FAILED files can be reprocessed (file is " + status + ")");
        }
        JsonNode source = store.require("recon_source", UUID.fromString(file.get("recon_source_id").asString()));
        String uri = file.get("file_uri").asString();
        return parseAndStore(file, source, files.get(uri), uri.endsWith(RECORDS_SUFFIX));
    }

    private String parseAndStore(JsonNode file, JsonNode source, byte[] bytes, boolean preParsed) {
        UUID fileId = UUID.fromString(file.get("id").asString());
        ParsedStatement parsed;
        try {
            parsed = parse(source, new String(bytes, StandardCharsets.UTF_8), preParsed);
            validate(parsed, source, file);
        } catch (ParsedStatement.ParseException e) {
            store.update("update recon_file set status = 'FAILED', error = ?, record_count = 0, updated_by = ? where id = ?",
                    e.getMessage(), TenantContext.principal(), fileId);
            return store.require("recon_file", fileId).toString();
        }
        for (StatementLine l : parsed.lines()) {
            ObjectNode r = json.createObjectNode();
            r.put("recon_file_id", fileId.toString());
            r.put("recon_source_id", source.get("id").asString());
            putIf(r, "external_ref", truncate(l.externalRef(), 128));
            putIf(r, "end_to_end_id", truncate(l.endToEndId(), 64));
            r.put("value_date", l.valueDate().toString());
            putIf(r, "booking_date", l.bookingDate() == null ? null : l.bookingDate().toString());
            r.put("direction", l.direction().name());
            r.put("amount", l.amount().toPlainString());
            r.put("currency", l.currency());
            putIf(r, "counterparty", truncate(l.counterparty(), 255));
            putIf(r, "narrative", truncate(l.narrative(), 500));
            r.set("raw", json.valueToTree(l.raw()));
            crud.insert("recon_record", r);
        }
        store.update("""
                update recon_file set status = 'PARSED', error = null, record_count = ?, updated_by = ?,
                       statement_date = coalesce(statement_date, ?), opening_balance = coalesce(opening_balance, ?),
                       closing_balance = coalesce(closing_balance, ?)
                where id = ?""", parsed.lines().size(), TenantContext.principal(), parsed.statementDate(),
                parsed.openingBalance(), parsed.closingBalance(), fileId);
        JsonNode row = store.require("recon_file", fileId);
        outbox.publish("ledger.recon_file.parsed", "recon_file", fileId, row);
        return row.toString();
    }

    private ParsedStatement parse(JsonNode source, String content, boolean preParsed) {
        String sourceCcy = Fields.text(source, "currency");
        sourceCcy = sourceCcy == null ? null : sourceCcy.trim();
        if (preParsed) {
            return fromJson(json.readTree(content), sourceCcy);
        }
        String format = source.get("file_format").asString();
        return switch (format) {
            case "CSV" -> CsvStatementParser.parse(content, csvConfig(source.path("parser_config"), sourceCcy));
            case "CAMT_053", "CAMT_054" -> CamtStatementParser.parse(content);
            case "JSON" -> {
                JsonNode n;
                try {
                    n = json.readTree(content);
                } catch (RuntimeException e) {
                    throw new ParsedStatement.ParseException("Content is not valid JSON");
                }
                yield fromJson(n.isObject() ? n.path("records") : n, sourceCcy);
            }
            default -> throw new ParsedStatement.ParseException("No parser for file format " + format
                    + "; send pre-parsed 'records' instead");
        };
    }

    /** Checks currencies against the source and, when both balances are known, opening + movements = closing. */
    private static void validate(ParsedStatement p, JsonNode source, JsonNode file) {
        if (p.lines().isEmpty()) {
            throw new ParsedStatement.ParseException("The file contains no records");
        }
        String ccy = Fields.text(source, "currency");
        BigDecimal net = BigDecimal.ZERO;
        for (StatementLine l : p.lines()) {
            if (ccy != null && !ccy.trim().equals(l.currency())) {
                throw new ParsedStatement.ParseException("Record currency " + l.currency()
                        + " differs from the source currency " + ccy.trim());
            }
            net = l.direction() == StatementLine.Direction.CREDIT ? net.add(l.amount()) : net.subtract(l.amount());
        }
        BigDecimal opening = Fields.decimal(file, "opening_balance") != null ? Fields.decimal(file, "opening_balance")
                : p.openingBalance();
        BigDecimal closing = Fields.decimal(file, "closing_balance") != null ? Fields.decimal(file, "closing_balance")
                : p.closingBalance();
        if (opening != null && closing != null && opening.add(net).compareTo(closing) != 0) {
            throw new ParsedStatement.ParseException("Balance check failed: opening " + opening.toPlainString()
                    + " + movements " + net.toPlainString() + " != closing " + closing.toPlainString());
        }
    }

    private static ParsedStatement fromJson(JsonNode arr, String defaultCcy) {
        if (arr == null || !arr.isArray()) {
            throw new ParsedStatement.ParseException("Expected an array of records");
        }
        List<StatementLine> lines = new ArrayList<>();
        int i = 0;
        for (JsonNode n : arr) {
            i++;
            try {
                String dir = Fields.requireText(n, "direction").toUpperCase(Locale.ROOT);
                StatementLine.Direction d = switch (dir) {
                    case "CREDIT", "CRDT", "C", "CR" -> StatementLine.Direction.CREDIT;
                    case "DEBIT", "DBIT", "D", "DR" -> StatementLine.Direction.DEBIT;
                    default -> throw new ParsedStatement.ParseException("Record " + i + ": unknown direction " + dir);
                };
                BigDecimal amount = Fields.requireDecimal(n, "amount");
                if (amount.signum() <= 0) {
                    throw new ParsedStatement.ParseException("Record " + i + ": amount must be positive");
                }
                String ccy = Fields.text(n, "currency") != null ? Fields.text(n, "currency") : defaultCcy;
                if (ccy == null || !ccy.matches("[A-Z]{3}")) {
                    throw new ParsedStatement.ParseException("Record " + i + ": missing or invalid currency");
                }
                Map<String, String> raw = new HashMap<>();
                n.propertyNames().forEach(k -> raw.put(k, n.get(k).asString()));
                lines.add(new StatementLine(Fields.text(n, "external_ref"), Fields.text(n, "end_to_end_id"),
                        Fields.requireDate(n, "value_date"), Fields.date(n, "booking_date"), d, amount, ccy,
                        Fields.text(n, "counterparty"), Fields.text(n, "narrative"), raw));
            } catch (ApiException e) {
                throw new ParsedStatement.ParseException("Record " + i + ": " + e.getMessage());
            }
        }
        LocalDate date = lines.stream().map(StatementLine::valueDate).max(LocalDate::compareTo).orElse(null);
        return new ParsedStatement(date, null, null, lines);
    }

    private static CsvStatementParser.Config csvConfig(JsonNode cfg, String defaultCcy) {
        CsvStatementParser.Config d = CsvStatementParser.Config.defaults(defaultCcy);
        if (cfg == null || !cfg.isObject()) {
            return d;
        }
        Map<String, String> columns = new HashMap<>();
        cfg.path("columns").propertyNames().forEach(k -> columns.put(k, cfg.path("columns").get(k).asString()));
        String delimiter = Fields.text(cfg, "delimiter");
        String dateFormat = Fields.text(cfg, "date_format");
        DateTimeFormatter fmt;
        try {
            fmt = dateFormat == null ? d.dateFormat() : DateTimeFormatter.ofPattern(dateFormat);
        } catch (IllegalArgumentException e) {
            throw new ParsedStatement.ParseException("Invalid parser_config.date_format " + dateFormat);
        }
        return new CsvStatementParser.Config(delimiter == null || delimiter.isEmpty() ? d.delimiter() : delimiter.charAt(0),
                cfg.path("has_header").asBoolean(true), columns, fmt,
                Fields.text(cfg, "currency") != null ? Fields.text(cfg, "currency") : defaultCcy,
                values(cfg.path("credit_values"), d.creditValues()), values(cfg.path("debit_values"), d.debitValues()));
    }

    private static Set<String> values(JsonNode arr, Set<String> fallback) {
        if (!arr.isArray() || arr.isEmpty()) {
            return fallback;
        }
        Set<String> out = new HashSet<>();
        arr.forEach(v -> out.add(v.asString().toUpperCase(Locale.ROOT)));
        return out;
    }

    private static void putIf(ObjectNode n, String k, String v) {
        if (v != null) {
            n.put(k, v);
        }
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
