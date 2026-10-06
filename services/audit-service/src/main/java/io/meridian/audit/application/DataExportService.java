package io.meridian.audit.application;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.audit.domain.ExportRules;
import io.meridian.audit.infrastructure.AuditEventStore;
import io.meridian.audit.infrastructure.DownloadUrlSigner;
import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.web.client.ServiceClient;
import io.meridian.web.crud.CrudEngine;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Regulator / DSAR / legal-discovery exports under four-eyes: a request is queued and an approval request is
 * opened in workflow-service; only an approval by someone other than the requester runs the export.
 *
 * <p>Approvals go through libs/meridian-approvals (G11). There is no local decision endpoint, so exports are
 * always sent to workflow-service whatever {@code meridian.approvals.mode} says; decisions come back through
 * {@link DataExportApprovalHandler}.
 */
@Service
public class DataExportService {
    public static final String RESOURCE_TYPE = "data_export_request";
    private static final Logger log = LoggerFactory.getLogger(DataExportService.class);

    private final CrudEngine crud;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final OutboxWriter outbox;
    private final Approvals approvals;
    private final AuditEventStore events;
    private final DownloadUrlSigner signer;

    public DataExportService(CrudEngine crud, JdbcClient jdbc, JsonMapper json, OutboxWriter outbox,
                             ServiceClient services, @Value("${spring.application.name}") String application,
                             AuditEventStore events, DownloadUrlSigner signer) {
        this.crud = crud;
        this.jdbc = jdbc;
        this.json = json;
        this.outbox = outbox;
        this.approvals = new Approvals(services, application, "workflow");
        this.events = events;
        this.signer = signer;
    }

    private record ExportRow(UUID id, String status, String createdBy, UUID approvalRequestId, String exportType,
                             OffsetDateTime fromTime, OffsetDateTime toTime, OffsetDateTime expiresAt, UUID documentId) {}

    @Transactional
    public String create(Object body, String idempotencyKey) {
        TenantContext.require();
        ObjectNode v = crud.toColumns(RESOURCE_TYPE, body);
        String type = v.path("export_type").asString(null);
        if (type == null) {
            throw ApiException.badRequest("validation_failed", "export_type is required");
        }
        OffsetDateTime from = time(v, "from_time");
        OffsetDateTime to = time(v, "to_time");
        if (from != null && to != null && !to.isAfter(from)) {
            throw ApiException.unprocessable("invalid_time_range", "to_time must be after from_time");
        }
        if (to != null && to.isAfter(io.meridian.common.time.Clocks.now().plusMinutes(5))) {
            throw ApiException.unprocessable("invalid_time_range", "to_time cannot be in the future");
        }
        if (ExportRules.SUBJECT_TYPES_REQUIRED.contains(type)
                && (!v.hasNonNull("subject_type") || !v.hasNonNull("subject_id"))) {
            throw ApiException.unprocessable("subject_required",
                    type + " exports need subject_type and subject_id (the data subject)");
        }
        if (v.hasNonNull("due_date")) {
            LocalDate due = LocalDate.parse(v.get("due_date").asString());
            if (due.isBefore(LocalDate.now(ZoneOffset.UTC))) {
                throw ApiException.unprocessable("invalid_due_date", "due_date cannot be in the past");
            }
        } else if ("DATA_SUBJECT_ACCESS".equals(type)) {
            v.put("due_date", LocalDate.now(ZoneOffset.UTC).plusDays(ExportRules.DSAR_DUE_DAYS).toString());
        }
        UUID id = Ids.newId();
        v.put("id", id.toString());
        v.put("status", "QUEUED");
        String row = crud.insert(RESOURCE_TYPE, v);

        String title = "Data export " + type + " for " + v.path("requested_for").asString("");
        JsonNode created = approvals.request(new ApprovalSpec(RESOURCE_TYPE, id, "execute",
                title.length() > 255 ? title.substring(0, 255) : title,
                "Export of " + type + (from == null ? "" : " from " + from) + (to == null ? "" : " to " + to)
                        + " requested by " + TenantContext.principal(),
                json.readTree(row), null, null, ExportRules.SUBJECT_TYPES_REQUIRED.contains(type) ? "HIGH" : "NORMAL"),
                "execute:" + id);
        if (created == null || !created.hasNonNull("id")) {
            throw new ApiException(502, "upstream_error", "workflow-service did not return an approval request id");
        }
        jdbc.sql("update data_export_request set approval_request_id = ?::uuid where id = ?")
                .params(created.get("id").asString(), id).update();
        return row(id);
    }

    /**
     * Executes an export once its approval request is fully approved (called by the approvals consumer).
     * Idempotent: an export that already succeeded is returned unchanged.
     */
    @Transactional
    public String applyApproved(JsonNode approval) {
        TenantContext.require();
        if (!RESOURCE_TYPE.equals(approval.path("resource_type").asString(null))) {
            throw ApiException.unprocessable("unsupported_resource", "Approval is not for a data export");
        }
        String approvalStatus = approval.path("status").asString("");
        if (!"APPROVED".equals(approvalStatus)) {
            throw ApiException.conflict("approval_not_approved", "Approval request is " + approvalStatus);
        }
        UUID exportId = UUID.fromString(approval.path("resource_id").asString());
        ExportRow e = lock(exportId);
        if (e.approvalRequestId() == null
                || !e.approvalRequestId().toString().equals(approval.path("id").asString())) {
            throw ApiException.conflict("approval_mismatch", "Approval does not belong to export " + exportId);
        }
        if ("SUCCEEDED".equals(e.status())) {
            return row(exportId);
        }
        if (!ExportRules.canTransition(e.status(), "RUNNING")) {
            throw ApiException.conflict("invalid_transition", "Export " + exportId + " is " + e.status());
        }
        String executor = TenantContext.principal();
        if (executor.equals(e.createdBy())) {
            throw ApiException.unprocessable("four_eyes_violation",
                    "The requester of an export cannot approve or execute it");
        }
        setStatus(exportId, "RUNNING");
        long rows = "AUDIT_LOG".equals(e.exportType()) ? events.count(e.fromTime(), e.toTime()) : 0L;
        UUID documentId = Ids.newId(); // placeholder until document-service stores the generated file
        Instant expires = Instant.now().plus(ExportRules.DOWNLOAD_VALIDITY_DAYS, ChronoUnit.DAYS);
        jdbc.sql("""
                update data_export_request set status = 'SUCCEEDED', row_count = ?, document_id = ?,
                       expires_at = ?, updated_by = ? where id = ?""")
                .params((int) Math.min(rows, Integer.MAX_VALUE), documentId, OffsetDateTime.ofInstant(expires, ZoneOffset.UTC),
                        executor, exportId)
                .update();
        String row = row(exportId);
        outbox.publish("platform.data_export_request.completed", RESOURCE_TYPE, exportId, json.readTree(row));
        return row;
    }

    @Transactional
    public String cancel(UUID id, String reason) {
        TenantContext.require();
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("validation_failed", "reason is required");
        }
        ExportRow e = lock(id);
        if (!ExportRules.canTransition(e.status(), "CANCELLED")) {
            throw ApiException.conflict("invalid_transition", "Export " + id + " is " + e.status()
                    + " and can no longer be cancelled");
        }
        setStatus(id, "CANCELLED");
        outbox.publish("platform.data_export_request.cancelled", RESOURCE_TYPE, id, json.readTree(row(id)));
        if (e.approvalRequestId() != null) {
            try {
                approvals.cancel(e.approvalRequestId(), truncate("Export cancelled: " + reason, 255));
            } catch (RuntimeException ex) {
                // The approval can no longer execute anything (export is CANCELLED); closing it is best effort.
                log.warn("Could not cancel approval request {} for export {}: {}", e.approvalRequestId(), id, ex.toString());
            }
        }
        return row(id);
    }

    /**
     * The export's approval request was rejected, sent back for changes or cancelled in workflow-service: a queued
     * export can never run (an export request cannot be amended), so it is cancelled. Idempotent.
     */
    @Transactional
    public String closeUnapproved(JsonNode approval, String outcome) {
        TenantContext.require();
        UUID exportId = UUID.fromString(approval.path("resource_id").asString());
        ExportRow e = lock(exportId);
        if (e.approvalRequestId() == null
                || !e.approvalRequestId().toString().equals(approval.path("id").asString())) {
            throw ApiException.conflict("approval_mismatch", "Approval does not belong to export " + exportId);
        }
        if (!"QUEUED".equals(e.status())) {
            return row(exportId);
        }
        setStatus(exportId, "CANCELLED");
        String row = row(exportId);
        outbox.publish("platform.data_export_request.cancelled", RESOURCE_TYPE, exportId, json.readTree(row));
        log.info("Export {} cancelled: approval {} {}", exportId, approval.path("id").asString(), outcome);
        return row;
    }

    @Transactional(readOnly = true)
    public JsonNode downloadUrl(UUID id) {
        UUID tenant = TenantContext.require();
        ExportRow e = jdbc.sql(SELECT + " where id = ?").param(id).query(this::map).optional()
                .orElseThrow(() -> ApiException.notFound("data export request", id));
        if (!"SUCCEEDED".equals(e.status()) && !"PARTIALLY_SUCCEEDED".equals(e.status())) {
            throw ApiException.conflict("export_not_ready", "Export " + id + " is " + e.status());
        }
        if (e.expiresAt() == null || e.expiresAt().toInstant().isBefore(Instant.now()) || e.documentId() == null) {
            throw ApiException.conflict("export_expired", "The download link for export " + id + " has expired");
        }
        ObjectNode r = json.createObjectNode();
        r.put("export_id", id.toString());
        r.put("document_id", e.documentId().toString());
        r.put("url", signer.sign(tenant, id, e.documentId(), e.expiresAt().toInstant()));
        r.put("expires_at", e.expiresAt().toInstant().toString());
        return r;
    }

    private static final String SELECT = """
            select id, status, created_by, approval_request_id, export_type, from_time, to_time, expires_at, document_id
            from data_export_request""";

    private ExportRow lock(UUID id) {
        return jdbc.sql(SELECT + " where id = ? for update").param(id).query(this::map).optional()
                .orElseThrow(() -> ApiException.notFound("data export request", id));
    }

    private ExportRow map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new ExportRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getObject(4, UUID.class),
                rs.getString(5), rs.getObject(6, OffsetDateTime.class), rs.getObject(7, OffsetDateTime.class),
                rs.getObject(8, OffsetDateTime.class), rs.getObject(9, UUID.class));
    }

    private void setStatus(UUID id, String status) {
        jdbc.sql("update data_export_request set status = ?, updated_by = ? where id = ?")
                .params(status, TenantContext.principal(), id).update();
    }

    private String row(UUID id) {
        return crud.findOne(RESOURCE_TYPE, Map.of("id", id)).orElseThrow(() -> ApiException.notFound("data export request", id));
    }

    private static OffsetDateTime time(ObjectNode v, String f) {
        if (!v.hasNonNull(f)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(v.get(f).asString());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("invalid_value", f + " must be an ISO-8601 date-time");
        }
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
