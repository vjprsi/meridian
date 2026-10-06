package io.meridian.document.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.document.domain.Lifecycles;
import io.meridian.document.infrastructure.DocumentStore;
import io.meridian.events.OutboxWriter;
import io.meridian.web.crud.CrudEngine;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Document lifecycle: registration, presigned upload/download, upload completion and virus-scan outcome,
 * legal hold, archive, retention-aware deletion and identity-document expiry alerts.
 */
@Service
public class DocumentService {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");
    /** Object storage class of archived content. */
    static final String ARCHIVE_STORAGE_CLASS = "GLACIER_IR";

    private final CrudEngine crud;
    private final DocumentStore store;
    private final ObjectStorage storage;
    private final OutboxWriter outbox;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Duration uploadTtl;
    private final Duration downloadTtl;

    public DocumentService(CrudEngine crud, DocumentStore store, ObjectStorage storage, OutboxWriter outbox,
                           JdbcClient jdbc, JsonMapper json,
                           @Value("${meridian.documents.upload-url-ttl:PT15M}") Duration uploadTtl,
                           @Value("${meridian.documents.download-url-ttl:PT5M}") Duration downloadTtl) {
        this.crud = crud;
        this.store = store;
        this.storage = storage;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.json = json;
        this.uploadTtl = uploadTtl;
        this.downloadTtl = downloadTtl;
    }

    // ------------------------------------------------------------------ create / update / delete

    @Transactional
    public String create(Object body) {
        ObjectNode values = crud.toColumns("document", body);
        validateContentType(values.path("content_type").asString(null));
        validateDates(values);
        normaliseCountry(values);
        UUID id = Ids.newId();
        String key = TenantContext.require() + "/" + id + "/" + Lifecycles.safeFileName(values.get("file_name").asString());
        values.put("id", id.toString());
        values.put("storage_uri", storage.uri(key));
        values.put("status", "UPLOADING");
        values.put("size_bytes", 0);
        values.put("checksum", "");
        if (!values.hasNonNull("uploaded_via")) {
            values.put("uploaded_via", "API");
        }
        return crud.insert("document", values);
    }

    /** Validates a PATCH before the generic update applies it (If-Match still guards concurrent changes). */
    @Transactional(readOnly = true)
    public void validateUpdate(UUID id, Object body) {
        JsonNode current = json.readTree(store.row("document", id));
        ObjectNode changes = crud.toColumns("document", body);
        String status = current.get("status").asString();
        if ("DELETED".equals(status)) {
            throw ApiException.conflict("document_deleted", "Document " + id + " is deleted");
        }
        if ((changes.has("file_name") || changes.has("content_type")) && !"UPLOADING".equals(status)) {
            throw ApiException.conflict("content_immutable", "File name and content type are fixed once content is uploaded");
        }
        if (changes.has("content_type")) {
            validateContentType(changes.get("content_type").asString());
        }
        if (changes.has("retention_until") && current.path("legal_hold").asBoolean(false)) {
            LocalDate before = date(current, "retention_until");
            LocalDate after = LocalDate.parse(changes.get("retention_until").asString());
            if (before != null && after.isBefore(before)) {
                throw ApiException.conflict("legal_hold_active", "Retention cannot be shortened while a legal hold is in place");
            }
        }
        ObjectNode merged = ((ObjectNode) current.deepCopy());
        merged.setAll(changes);
        validateDates(merged);
        if (changes.has("issuing_country")) {
            normaliseCountry(changes);
        }
    }

    /** Soft delete: blocked by legal hold and by the retention period; content purge is asynchronous. */
    @Transactional
    public void delete(UUID id) {
        JsonNode d = store.lock("document", id);
        String status = d.get("status").asString();
        if ("DELETED".equals(status)) {
            throw ApiException.notFound("document", id);
        }
        if (d.path("legal_hold").asBoolean(false)) {
            throw ApiException.conflict("legal_hold_active", "Document " + id + " is under legal hold and cannot be deleted");
        }
        LocalDate retention = date(d, "retention_until");
        if (retention != null && retention.isAfter(LocalDate.now())) {
            throw ApiException.conflict("retention_period_active",
                    "Document " + id + " must be retained until " + retention);
        }
        transition(id, status, "DELETED");
        outbox.publish("platform.document.deleted", "document", id, event(store.row("document", id)));
    }

    // ------------------------------------------------------------------ content

    @Transactional(readOnly = true)
    public Map<String, Object> uploadUrl(UUID id) {
        JsonNode d = json.readTree(store.row("document", id));
        String status = d.get("status").asString();
        if (!"UPLOADING".equals(status)) {
            throw ApiException.conflict("invalid_transition", "Upload is only possible while the document is UPLOADING (now " + status + ")");
        }
        String key = storage.keyOf(storageUri(id));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("document_id", id.toString());
        r.put("method", "PUT");
        r.put("url", storage.presign("PUT", key, uploadTtl, Map.of()));
        r.put("headers", Map.of("Content-Type", d.get("content_type").asString()));
        r.put("expires_at", Instant.now().plus(uploadTtl).toString());
        return r;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> downloadUrl(UUID id) {
        JsonNode d = json.readTree(store.row("document", id));
        String status = d.get("status").asString();
        if (!"AVAILABLE".equals(status) && !"ARCHIVED".equals(status)) {
            throw ApiException.conflict("document_not_downloadable", "Document " + id + " is " + status + " and cannot be downloaded");
        }
        String key = storage.keyOf(storageUri(id));
        String fileName = Lifecycles.safeFileName(d.get("file_name").asString());
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("document_id", id.toString());
        r.put("method", "GET");
        r.put("url", storage.presign("GET", key, downloadTtl, Map.of(
                "response-content-disposition", "attachment; filename=\"" + fileName + "\"",
                "response-content-type", d.get("content_type").asString())));
        r.put("expires_at", Instant.now().plus(downloadTtl).toString());
        return r;
    }

    /** Presigned GET for internal use (e-sign providers fetch the document). */
    public String internalDownloadUrl(UUID id, Duration ttl) {
        return storage.presign("GET", storage.keyOf(storageUri(id)), ttl, Map.of());
    }

    /**
     * Upload finished (object-store notification or uploader confirmation): records size and checksum and moves
     * the document to SCANNING; with a scan result it completes immediately.
     */
    @Transactional
    public String completeUpload(UUID id, long sizeBytes, String checksum, String scanResult) {
        JsonNode d = store.lock("document", id);
        String status = d.get("status").asString();
        if (!"UPLOADING".equals(status)) {
            throw ApiException.conflict("invalid_transition", "Document " + id + " is " + status + ", not UPLOADING");
        }
        if (sizeBytes <= 0) {
            throw ApiException.unprocessable("invalid_size", "Uploaded size must be positive");
        }
        String sum = checksum == null ? "" : checksum.toLowerCase(Locale.ROOT);
        if (!SHA256.matcher(sum).matches()) {
            throw ApiException.unprocessable("invalid_checksum", "Checksum must be a hex SHA-256");
        }
        jdbc.sql("update document set size_bytes = ?, checksum = ?, status = 'SCANNING', updated_by = ? where id = ?")
                .params(sizeBytes, sum, TenantContext.principal(), id).update();
        return scanResult == null ? store.row("document", id) : recordScan(id, scanResult);
    }

    /** Virus-scan outcome: CLEAN makes the document available, INFECTED quarantines it, ERROR keeps it scanning. */
    @Transactional
    public String recordScan(UUID id, String result) {
        JsonNode d = store.lock("document", id);
        String status = d.get("status").asString();
        if (!"SCANNING".equals(status)) {
            throw ApiException.conflict("invalid_transition", "Document " + id + " is " + status + ", not SCANNING");
        }
        String r = result == null ? "" : result.toUpperCase(Locale.ROOT);
        switch (r) {
            case "CLEAN" -> {
                setScan(id, "CLEAN", "AVAILABLE");
                String row = store.row("document", id);
                outbox.publish("platform.document.uploaded", "document", id, event(row));
                return row;
            }
            case "INFECTED" -> {
                setScan(id, "INFECTED", "QUARANTINED");
                String row = store.row("document", id);
                outbox.publish("platform.document.quarantined", "document", id, event(row));
                return row;
            }
            case "ERROR" -> {
                jdbc.sql("update document set virus_scan_result = 'ERROR', updated_by = ? where id = ?")
                        .params(TenantContext.principal(), id).update();
                return store.row("document", id);
            }
            default -> throw ApiException.unprocessable("invalid_scan_result", "Scan result must be CLEAN, INFECTED or ERROR");
        }
    }

    // ------------------------------------------------------------------ retention

    @Transactional
    public String legalHold(UUID id, boolean hold, String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.unprocessable("reason_required", "A reason is required to place or lift a legal hold");
        }
        JsonNode d = store.lock("document", id);
        if ("DELETED".equals(d.get("status").asString())) {
            throw ApiException.conflict("document_deleted", "Document " + id + " is deleted");
        }
        boolean current = d.path("legal_hold").asBoolean(false);
        if (current == hold) {
            throw ApiException.conflict("invalid_transition",
                    hold ? "Document is already under legal hold" : "Document is not under legal hold");
        }
        jdbc.sql("""
                update document set legal_hold = ?, legal_hold_reason = ?, legal_hold_changed_at = now(),
                       legal_hold_changed_by = ?, updated_by = ?
                where id = ?""")
                .params(hold, reason, TenantContext.principal(), TenantContext.principal(), id).update();
        String row = store.row("document", id);
        outbox.publish(hold ? "platform.document.legal_hold_placed" : "platform.document.legal_hold_lifted",
                "document", id, event(row));
        return row;
    }

    @Transactional
    public String archive(UUID id) {
        JsonNode d = store.lock("document", id);
        String status = d.get("status").asString();
        transition(id, status, "ARCHIVED");
        jdbc.sql("update document set archived_at = now(), storage_class = ? where id = ?")
                .params(ARCHIVE_STORAGE_CLASS, id).update();
        String row = store.row("document", id);
        outbox.publish("platform.document.archived", "document", id, event(row));
        return row;
    }

    /** Publishes {@code platform.document.expiring} once per identity document expiring within the window. */
    @Transactional
    public int notifyExpiring(LocalDate today, int daysAhead) {
        List<String> rows = jdbc.sql("""
                select row_to_json(d)::text from document d
                where status = 'AVAILABLE' and document_type = any(?) and expires_on is not null
                  and expires_on between ? and ? and expiry_notified_on is null
                for update skip locked""")
                .params(Lifecycles.IDENTITY_TYPES.toArray(String[]::new), today, today.plusDays(daysAhead))
                .query(String.class).list();
        for (String row : rows) {
            UUID id = UUID.fromString(json.readTree(row).get("id").asString());
            jdbc.sql("update document set expiry_notified_on = ? where id = ?").params(today, id).update();
            outbox.publish("platform.document.expiring", "document", id, event(store.row("document", id)));
        }
        return rows.size();
    }

    // ------------------------------------------------------------------ helpers

    /** Event payload: the API representation (hidden storage and bookkeeping columns removed). */
    public JsonNode event(String row) {
        ObjectNode n = (ObjectNode) json.readTree(row);
        n.remove(Set.of("storage_uri", "encryption_key_id", "expiry_notified_on", "tenant_id"));
        return n;
    }

    private String storageUri(UUID id) {
        return jdbc.sql("select storage_uri from document where id = ?").param(id).query(String.class).single();
    }

    private void setScan(UUID id, String result, String status) {
        jdbc.sql("update document set virus_scan_result = ?, status = ?, updated_by = ? where id = ?")
                .params(result, status, TenantContext.principal(), id).update();
    }

    private void transition(UUID id, String from, String to) {
        try {
            Lifecycles.require(Lifecycles.DOCUMENT, "Document", from, to);
        } catch (Lifecycles.InvalidTransition e) {
            throw ApiException.conflict(e.code(), e.getMessage());
        }
        jdbc.sql("update document set status = ?, updated_by = ? where id = ?")
                .params(to, TenantContext.principal(), id).update();
    }

    private static void validateContentType(String contentType) {
        if (contentType == null) {
            return;
        }
        String base = contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        if (!Lifecycles.ALLOWED_CONTENT_TYPES.contains(base)) {
            throw ApiException.unprocessable("unsupported_content_type", "Content type " + contentType + " is not accepted");
        }
    }

    private static void validateDates(JsonNode values) {
        LocalDate issued = date(values, "issued_on");
        LocalDate expires = date(values, "expires_on");
        if (issued != null && issued.isAfter(LocalDate.now())) {
            throw ApiException.unprocessable("invalid_issue_date", "issued_on cannot be in the future");
        }
        if (issued != null && expires != null && !expires.isAfter(issued)) {
            throw ApiException.unprocessable("invalid_expiry_date", "expires_on must be after issued_on");
        }
    }

    private static void normaliseCountry(ObjectNode values) {
        if (values.hasNonNull("issuing_country")) {
            String c = values.get("issuing_country").asString().toUpperCase(Locale.ROOT);
            if (!COUNTRY.matcher(c).matches()) {
                throw ApiException.unprocessable("invalid_country", "issuing_country must be an ISO 3166 alpha-2 code");
            }
            values.put("issuing_country", c);
        }
    }

    private static LocalDate date(JsonNode n, String field) {
        return n.hasNonNull(field) ? LocalDate.parse(n.get(field).asString()) : null;
    }
}
