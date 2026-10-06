package io.meridian.cardclearing.application;

import io.meridian.cardclearing.application.RecordProcessor.Outcome;
import io.meridian.cardclearing.domain.ClearingFileParser;
import io.meridian.cardclearing.domain.ClearingMatcher.AuthView;
import io.meridian.cardclearing.domain.ParsedRecord;
import io.meridian.cardclearing.domain.Settlement;
import io.meridian.cardclearing.infrastructure.Clients;
import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Clearing file intake: de-duplication by SHA-256, strict parsing, per-record matching and posting (each record
 * committed with its fees and events in its own transaction, after its ledger calls), settlement positions and file
 * totals.
 *
 * <p>Reprocess: the raw content is write-only (never stored), so a file that FAILED parsing cannot be reprocessed
 * (422 file_not_reprocessable: upload the corrected file). For a parsed file, every UNMATCHED / EXCEPTION record
 * without a resolving record is matched and posted again; when that succeeds a resolving record is appended
 * (force_matched_from_id = the original, match_rule = original rule + "_RETRY"); records that still fail are left
 * as they are. Settlement positions are not changed by reprocessing (the scheme amounts were already counted).
 */
@Service
public class ClearingFileService {
    private static final Set<String> OPEN = Set.of("UNMATCHED", "EXCEPTION");

    private final ClearingStore store;
    private final RecordProcessor processor;
    private final SettlementService settlements;
    private final Clients clients;
    private final Events events;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public ClearingFileService(ClearingStore store, RecordProcessor processor, SettlementService settlements,
                               Clients clients, Events events, JdbcClient jdbc, TransactionTemplate tx) {
        this.store = store;
        this.processor = processor;
        this.settlements = settlements;
        this.clients = clients;
        this.events = events;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public JsonNode upload(JsonNode req) {
        String scheme = text(req, "scheme");
        String format = text(req, "file_format") == null ? "CSV" : text(req, "file_format");
        String direction = text(req, "direction") == null ? "INCOMING" : text(req, "direction");
        String content = text(req, "content");
        JsonNode records = req.get("records");
        boolean hasRecords = records != null && records.isArray() && !records.isEmpty();
        if ((content == null || content.isBlank()) == !hasRecords) {
            throw ApiException.unprocessable("content_required", "Send either content or records (exactly one)");
        }
        if (!"INCOMING".equals(direction)) {
            throw ApiException.unprocessable("direction_not_supported", "Only INCOMING clearing files can be uploaded");
        }
        String canonical = hasRecords ? store.json().writeValueAsString(records) : content;
        String checksum = sha256(canonical);
        Long dup = jdbc.sql("select count(*) from clearing_file where checksum = ?").param(checksum)
                .query(Long.class).single();
        if (dup > 0) {
            throw ApiException.conflict("duplicate_file", "A clearing file with the same content was already received");
        }
        ObjectNode v = store.json().createObjectNode();
        UUID fileId = Ids.newId();
        v.put("id", fileId.toString());
        v.put("scheme", scheme);
        v.put("direction", direction);
        v.put("file_format", hasRecords ? "JSON" : format);
        v.put("file_name", text(req, "file_name"));
        v.put("file_uri", "inline://clearing/" + checksum);
        v.put("checksum", checksum);
        v.put("processing_date", text(req, "processing_date"));
        copy(req, v, "card_program_id", "currency", "custom_field_1", "custom_field_2", "custom_field_3",
                "custom_field_4", "custom_field_5");
        v.put("status", "PARSING");
        JsonNode file;
        try {
            file = tx.execute(s -> store.insert("clearing_file", v));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("duplicate_file", "A clearing file with the same content was already received");
        }
        ClearingFileParser.Result parsed = parse(hasRecords ? records : null, content, format);
        if (!parsed.ok()) {
            return tx.execute(s -> {
                ObjectNode upd = store.json().createObjectNode();
                upd.put("status", "FAILED");
                upd.put("error", String.join("\n", parsed.errors()));
                upd.put("record_count", 0);
                JsonNode failed = store.update("clearing_file", fileId, upd);
                events.publish("cards.clearing_file.failed", "clearing_file", failed);
                return failed;
            });
        }
        UUID fileProgram = uuid(file, "card_program_id");
        Map<List<Object>, Settlement.Totals> positions = new LinkedHashMap<>();
        int matched = 0;
        int unmatched = 0;
        int exceptions = 0;
        BigDecimal total = BigDecimal.ZERO;
        Set<String> currencies = new java.util.HashSet<>();
        for (ParsedRecord r : parsed.records()) {
            Outcome o = processor.process(Ids.newId(), scheme, fileProgram, r, null);
            tx.executeWithoutResult(s -> store.insertRecord(fileId, scheme, r, o, null));
            switch (o.matchStatus) {
                case "MATCHED", "FORCE_POST" -> matched++;
                case "UNMATCHED" -> unmatched++;
                default -> exceptions++;
            }
            total = total.add(r.billingAmount());
            currencies.add(r.billingCurrency());
            positions.computeIfAbsent(java.util.Arrays.asList(fileProgram != null ? fileProgram : o.programId,
                    r.settlementCurrency()), k -> new Settlement.Totals())
                    .add(r.recordType(), r.settlementAmount(), o.interchange, r.schemeFeeAmount());
        }
        final int m = matched;
        final int u = unmatched;
        final int x = exceptions;
        final BigDecimal t = total;
        final String ccy = currencies.size() == 1 ? currencies.iterator().next() : null;
        return tx.execute(s -> {
            List<String> problems = new ArrayList<>();
            positions.forEach((key, totals) -> {
                String problem = settlements.accumulate(scheme, (UUID) key.get(0),
                        java.time.LocalDate.parse(text(file, "processing_date")), (String) key.get(1), totals);
                if (problem != null) {
                    problems.add(problem);
                }
            });
            ObjectNode upd = store.json().createObjectNode();
            upd.put("status", "POSTED");
            upd.put("record_count", parsed.records().size());
            upd.put("matched_count", m);
            upd.put("unmatched_count", u);
            upd.put("exception_count", x);
            upd.put("total_amount", t.toPlainString());
            if (ccy != null && text(file, "currency") == null) {
                upd.put("currency", ccy);
            }
            if (!problems.isEmpty()) {
                upd.put("error", String.join("\n", problems));
            }
            JsonNode done = store.update("clearing_file", fileId, upd);
            events.publish("cards.clearing_file.processed", "clearing_file", done);
            return done;
        });
    }

    public JsonNode reprocess(UUID fileId) {
        JsonNode file = store.row("clearing_file", fileId);
        String status = text(file, "status");
        if ("FAILED".equals(status)) {
            throw ApiException.unprocessable("file_not_reprocessable",
                    "The file failed parsing and its content is not stored; upload the corrected file");
        }
        if (!"POSTED".equals(status)) {
            throw ApiException.conflict("invalid_transition", "Only posted files can be reprocessed (file is " + status + ")");
        }
        String scheme = text(file, "scheme");
        for (JsonNode rec : store.recordsOfFile(fileId)) {
            if (!OPEN.contains(text(rec, "match_status")) || store.resolved(uuid(rec, "id"))) {
                continue;
            }
            ParsedRecord r = ClearingStore.parsed(rec);
            Outcome o = processor.process(Ids.newId(), scheme, uuid(file, "card_program_id"), r, null);
            if (!o.posted()) {
                continue;
            }
            o.matchRule = (text(rec, "match_rule") == null ? "NONE" : text(rec, "match_rule")) + "_RETRY";
            if (o.matchRule.length() > 32) {
                o.matchRule = o.matchRule.substring(0, 32);
            }
            UUID original = uuid(rec, "id");
            tx.executeWithoutResult(s -> store.insertRecord(fileId, scheme, r, o, original));
        }
        return tx.execute(s -> refreshCounts(fileId));
    }

    /** Appends a MATCHED record resolving an unmatched / exception record against an operator-chosen authorisation. */
    public JsonNode forceMatch(UUID recordId, UUID authId, String reason) {
        JsonNode rec = store.row("clearing_record", recordId);
        if (!OPEN.contains(text(rec, "match_status"))) {
            throw ApiException.conflict("invalid_transition",
                    "Only UNMATCHED or EXCEPTION records can be force-matched (record is " + text(rec, "match_status") + ")");
        }
        if (store.resolved(recordId)) {
            throw ApiException.conflict("already_resolved", "The record was already resolved");
        }
        AuthView auth = clients.authorization(authId).orElseThrow(() -> ApiException.unprocessable(
                "authorization_not_found", "card authorization " + authId + " was not found"));
        UUID card = uuid(rec, "card_id");
        if (card != null && !card.equals(auth.cardId())) {
            throw ApiException.unprocessable("card_mismatch", "The authorisation belongs to another card");
        }
        if (auth.billingCurrency() != null && !auth.billingCurrency().equals(text(rec, "billing_currency"))) {
            throw ApiException.unprocessable("currency_mismatch", "The authorisation is in " + auth.billingCurrency());
        }
        ParsedRecord r = ClearingStore.parsed(rec);
        UUID fileId = uuid(rec, "clearing_file_id");
        JsonNode file = store.row("clearing_file", fileId);
        Outcome o = processor.process(Ids.newId(), text(rec, "scheme"), uuid(file, "card_program_id"), r, auth);
        if (!o.posted()) {
            throw ApiException.unprocessable("force_match_failed", o.exceptionReason == null
                    ? "the record could not be posted" : o.exceptionReason);
        }
        o.matchRule = "FORCE";
        o.note("force-matched: " + reason);
        return tx.execute(s -> {
            JsonNode created = store.insertRecord(fileId, text(rec, "scheme"), r, o, recordId);
            events.publish("cards.clearing_record.force_matched", "clearing_record", created);
            refreshCounts(fileId);
            return created;
        });
    }

    /** Recounts a file's outcome: each original record counts with the status of its last resolving record. */
    JsonNode refreshCounts(UUID fileId) {
        List<JsonNode> all = store.recordsOfFile(fileId);
        Map<UUID, JsonNode> resolvedBy = new HashMap<>();
        for (JsonNode r : all) {
            UUID from = uuid(r, "force_matched_from_id");
            if (from != null) {
                resolvedBy.put(from, r);
            }
        }
        int m = 0;
        int u = 0;
        int x = 0;
        for (JsonNode r : all) {
            if (uuid(r, "force_matched_from_id") != null) {
                continue;
            }
            JsonNode last = r;
            while (resolvedBy.containsKey(uuid(last, "id"))) {
                last = resolvedBy.get(uuid(last, "id"));
            }
            switch (Objects.requireNonNull(text(last, "match_status"))) {
                case "MATCHED", "FORCE_POST" -> m++;
                case "UNMATCHED" -> u++;
                default -> x++;
            }
        }
        ObjectNode upd = store.json().createObjectNode();
        upd.put("matched_count", m);
        upd.put("unmatched_count", u);
        upd.put("exception_count", x);
        return store.update("clearing_file", fileId, upd);
    }

    private ClearingFileParser.Result parse(JsonNode records, String content, String format) {
        if (records != null) {
            return ClearingFileParser.parseRows(maps(records));
        }
        if ("JSON".equals(format)) {
            JsonNode arr;
            try {
                arr = store.json().readTree(content);
            } catch (JacksonException e) {
                return new ClearingFileParser.Result(List.of(), List.of("content is not valid JSON"));
            }
            if (!arr.isArray()) {
                return new ClearingFileParser.Result(List.of(), List.of("JSON content must be an array of records"));
            }
            return ClearingFileParser.parseRows(maps(arr));
        }
        return ClearingFileParser.parseCsv(content);
    }

    private static List<Map<String, String>> maps(JsonNode arr) {
        List<Map<String, String>> out = new ArrayList<>();
        for (JsonNode n : arr) {
            Map<String, String> m = new LinkedHashMap<>();
            if (n.isObject()) {
                Iterator<String> it = n.propertyNames().iterator();
                while (it.hasNext()) {
                    String k = it.next();
                    JsonNode v = n.get(k);
                    m.put(k, v == null || v.isNull() ? null : v.isValueNode() ? v.asString() : v.toString());
                }
            }
            out.add(m);
        }
        return out;
    }

    private static void copy(JsonNode from, ObjectNode to, String... fields) {
        for (String f : fields) {
            String v = text(from, f);
            if (v != null) {
                to.put(f, v);
            }
        }
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String text(JsonNode n, String f) {
        return ClearingStore.text(n, f);
    }

    static UUID uuid(JsonNode n, String f) {
        return ClearingStore.uuid(n, f);
    }
}
