package io.meridian.gl.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.gl.infrastructure.FileStore;
import io.meridian.gl.infrastructure.GlStore;
import io.meridian.web.crud.CrudEngine;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * ERP export of a closed period: the trial balance (one line per GL account and currency) as CSV, stored in the file
 * store with its SHA-256 checksum; {@code ledger.erp_export.completed} is published. Download returns the file.
 */
@Service
public class ErpExportService {
    static final Set<String> TARGETS = Set.of("SAP", "ORACLE", "NETSUITE", "DYNAMICS", "CSV");
    static final String HEADER = "legal_entity_id,period_type,period_start,period_end,gl_code,erp_code,gl_name,"
            + "account_class,currency,opening_balance,debits,credits,closing_balance,base_closing_balance";
    private static final long URL_TTL_SECONDS = 900;

    /** The export file content with its SHA-256 checksum and a suggested file name. */
    public record Download(String content, String checksum, String fileName) {}

    private final GlStore store;
    private final CrudEngine crud;
    private final FileStore files;
    private final OutboxWriter outbox;
    private final JsonMapper json;

    public ErpExportService(GlStore store, CrudEngine crud, FileStore files, OutboxWriter outbox, JsonMapper json) {
        this.store = store;
        this.crud = crud;
        this.files = files;
        this.outbox = outbox;
        this.json = json;
    }

    @Transactional
    public String create(Object body) {
        ObjectNode v = crud.toColumns("erp_export", body);
        UUID le = Fields.requireUuid(v, "legal_entity_id");
        UUID periodId = Fields.requireUuid(v, "accounting_period_id");
        String target = Fields.requireText(v, "target_system");
        if (!TARGETS.contains(target)) {
            throw Fields.invalid("target_system", "invalid_enum", "target_system must be one of " + TARGETS);
        }
        JsonNode period = store.find("accounting_period", periodId).orElseThrow(() ->
                ApiException.unprocessable("period_not_found", "Accounting period " + periodId + " does not exist"));
        if (!le.toString().equals(period.get("legal_entity_id").asString())) {
            throw ApiException.unprocessable("legal_entity_mismatch", "The period belongs to another legal entity");
        }
        if (!"CLOSED".equals(period.get("status").asString())) {
            throw ApiException.conflict("period_not_closed", "Only CLOSED periods can be exported (period is "
                    + period.get("status").asString() + ")");
        }
        List<JsonNode> rows = store.rows("""
                select json_build_object('code', a.code, 'erp_code', a.erp_code, 'name', a.name,
                       'account_class', a.account_class, 'currency', b.currency, 'opening_balance', b.opening_balance,
                       'debits', b.debits, 'credits', b.credits, 'closing_balance', b.closing_balance,
                       'base_closing_balance', b.base_closing_balance)::text
                from gl_balance b join gl_account a on a.id = b.gl_account_id
                where b.accounting_period_id = ? order by a.code, b.currency""", periodId);
        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        for (JsonNode r : rows) {
            csv.append(String.join(",", List.of(le.toString(), period.get("period_type").asString(),
                    period.get("start_date").asString(), period.get("end_date").asString(), cell(r, "code"),
                    cell(r, "erp_code"), cell(r, "name"), cell(r, "account_class"), cell(r, "currency"),
                    cell(r, "opening_balance"), cell(r, "debits"), cell(r, "credits"), cell(r, "closing_balance"),
                    cell(r, "base_closing_balance")))).append('\n');
        }
        byte[] content = csv.toString().getBytes(StandardCharsets.UTF_8);
        UUID id = Ids.newId();
        String uri = files.put("erp-exports/" + TenantContext.require() + "/" + id + ".csv", content);
        v.put("id", id.toString());
        v.put("status", "SUCCEEDED");
        v.put("file_uri", uri);
        v.put("line_count", rows.size());
        v.put("checksum", sha256(content));
        String row = crud.insert("erp_export", v);
        outbox.publish("ledger.erp_export.completed", "erp_export", id, json.readTree(row));
        return row;
    }

    /** The export file (CSV) of a SUCCEEDED export. */
    @Transactional(readOnly = true)
    public Download download(UUID id) {
        JsonNode e = store.require("erp_export", id);
        if (!"SUCCEEDED".equals(e.get("status").asString()) || !e.hasNonNull("file_uri")) {
            throw ApiException.conflict("export_not_ready", "Export " + id + " is " + e.get("status").asString());
        }
        return new Download(new String(files.get(e.get("file_uri").asString()), StandardCharsets.UTF_8),
                e.get("checksum").asString(), "gl-" + e.get("target_system").asString().toLowerCase(java.util.Locale.ROOT)
                        + "-" + id + ".csv");
    }

    private static String cell(JsonNode r, String f) {
        String v = r.hasNonNull(f) ? r.get(f).asString() : "";
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
