package io.meridian.regulatoryreporting.application;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.regulatoryreporting.domain.FilingCalendar;
import io.meridian.regulatoryreporting.domain.Lifecycles;
import io.meridian.regulatoryreporting.domain.ReportRenderer;
import io.meridian.regulatoryreporting.infrastructure.Store;
import io.meridian.web.crud.CrudEngine;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Regulator submissions: prepared (PENDING_APPROVAL) from a clean run's current output, signed off by a different
 * principal, transmitted through the {@link RegulatorGateway}, then acknowledged or rejected by the regulator.
 * A schedule with {@code auto_submit} transmits right after sign-off.
 */
@Service
public class SubmissionService {
    private final Store store;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final JsonMapper json;
    private final RegulatorGateway gateway;
    private final Approvals approvals;

    /** Approval resource type for submission sign-off in workflow-service (G11). */
    public static final String APPROVAL_RESOURCE = "report_submission";

    public SubmissionService(Store store, CrudEngine crud, OutboxWriter outbox, JsonMapper json, RegulatorGateway gateway,
                             Approvals approvals) {
        this.approvals = approvals;
        this.store = store;
        this.crud = crud;
        this.outbox = outbox;
        this.json = json;
        this.gateway = gateway;
    }

    @Transactional
    public String create(JsonNode req) {
        UUID runId = Fields.requireUuid(req, "report_run_id");
        UUID outputId = Fields.requireUuid(req, "report_output_id");
        String channel = Fields.requireText(req, "channel");
        JsonNode run = store.row("select row_to_json(t)::text from report_run t where t.id = ? for update", runId)
                .orElseThrow(() -> ApiException.unprocessable("run_not_found", "Report run " + runId + " does not exist"));
        String st = run.get("status").asString();
        if ("PARTIALLY_SUCCEEDED".equals(st)) {
            throw ApiException.unprocessable("run_has_validation_errors", "The run has " + run.path("error_count").asInt()
                    + " validation error(s); fix them with adjustments or rerun");
        }
        if (!"SUCCEEDED".equals(st)) {
            throw ApiException.conflict("run_not_completed", "Only SUCCEEDED runs can be submitted (run is " + st + ")");
        }
        if (run.hasNonNull("superseded_by_run_id")) {
            throw ApiException.conflict("run_superseded", "The run was superseded by " + run.get("superseded_by_run_id").asString());
        }
        JsonNode output = store.find("report_output", outputId).orElseThrow(() ->
                ApiException.unprocessable("output_not_found", "Report output " + outputId + " does not exist"));
        if (!runId.toString().equals(output.get("report_run_id").asString())) {
            throw ApiException.unprocessable("output_run_mismatch", "The output belongs to another run");
        }
        if (output.get("revision").asInt() != run.path("revision").asInt(1)) {
            throw ApiException.unprocessable("output_outdated", "The output renders revision " + output.get("revision").asInt()
                    + " but the run is at revision " + run.path("revision").asInt(1));
        }
        if (store.count("select count(*) from report_submission where report_run_id = ? and status in "
                + "('PENDING_APPROVAL', 'APPROVED', 'SUBMITTED', 'ACKNOWLEDGED', 'ACCEPTED')", runId) > 0) {
            throw ApiException.conflict("submission_exists", "The run already has an open submission");
        }
        ObjectNode v = crud.toColumns("report_submission", req);
        v.put("status", "PENDING_APPROVAL");
        JsonNode def = store.require("report_definition", RunService.uuid(run, "report_definition_id"));
        if (!req.hasNonNull("deadline_at")) {
            LocalDate due = FilingCalendar.dueDate(Fields.text(def, "deadline_rule"), def.get("frequency").asString(),
                    LocalDate.parse(run.get("period_end").asString()), def.path("filing_offset_days").asInt(0),
                    DefinitionService.conventions(def));
            v.put("deadline_at", due.atTime(23, 59, 59).atOffset(ZoneOffset.UTC).toString());
        }
        String inserted = crud.insert("report_submission", v);
        // Central maker-checker: the checker signs off in the workflow inbox; SubmissionApprovalHandler applies it.
        JsonNode row = json.readTree(inserted);
        UUID id = UUID.fromString(row.get("id").asString());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("report_code", def.get("code").asString());
        payload.put("regulator", Fields.text(def, "regulator"));
        payload.put("report_run_id", runId.toString());
        payload.put("period_end", Fields.text(run, "period_end"));
        payload.put("run_revision", run.path("revision").asInt(1));
        payload.put("report_output_id", outputId.toString());
        payload.put("file_name", Fields.text(output, "file_name"));
        payload.put("sha256", Fields.text(output, "sha256"));
        payload.put("channel", channel);
        payload.put("deadline_at", Fields.text(row, "deadline_at"));
        JsonNode request = approvals.request(ApprovalSpec.of(APPROVAL_RESOURCE, id, "approve",
                "Sign off " + def.get("code").asString() + " filing for " + Fields.text(run, "period_end"), payload),
                "signoff:" + id);
        UUID requestId = Approvals.idOf(request);
        if (requestId == null) {
            return inserted;
        }
        return store.updateReturning("""
                update report_submission t set approval_request_id = ? where t.id = ? returning row_to_json(t)::text""",
                requestId, id).toString();
    }

    /**
     * True while the submission awaits sign-off for this approval request (when one was recorded) and the file with
     * the submitted checksum.
     */
    @Transactional(readOnly = true)
    public boolean awaitsSignOff(UUID id, UUID approvalRequestId, String sha256) {
        return store.find("report_submission", id)
                .filter(s -> "PENDING_APPROVAL".equals(s.path("status").asString()))
                .filter(s -> approvalRequestId == null || !s.hasNonNull("approval_request_id")
                        || approvalRequestId.toString().equals(s.get("approval_request_id").asString()))
                .filter(s -> sha256 == null || store.find("report_output", RunService.uuid(s, "report_output_id"))
                        .map(o -> sha256.equals(Fields.text(o, "sha256"))).orElse(false))
                .isPresent();
    }

    /**
     * Sign-off refused by the checker (workflow-service rejection): the submission is closed as REJECTED so the run
     * can be corrected and submitted again. Four eyes as for sign-off.
     */
    @Transactional
    public String declineSignOff(UUID id, String reason) {
        JsonNode sub = store.requireLocked("report_submission", id);
        String st = sub.get("status").asString();
        if (!"PENDING_APPROVAL".equals(st)) {
            throw ApiException.conflict("invalid_transition", "A submission in status " + st + " cannot be declined");
        }
        if (TenantContext.principal().equals(Fields.text(sub, "created_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "The preparer of a submission cannot decide on it");
        }
        JsonNode row = store.updateReturning("""
                update report_submission t set status = 'REJECTED', rejection_reason = ?, updated_by = ?
                where t.id = ? returning row_to_json(t)::text""", reason, TenantContext.principal(), id);
        outbox.publish("reporting.report_submission.rejected", "report_submission", id, row);
        return row.toString();
    }

    /** Sign-off by a principal other than the preparer; auto-submits for auto_submit schedules. */
    @Transactional
    public String approve(UUID id, String comment) {
        JsonNode sub = store.requireLocked("report_submission", id);
        Lifecycles.submission(sub.get("status").asString(), "APPROVED");
        if (TenantContext.principal().equals(Fields.text(sub, "created_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "The preparer of a submission cannot sign it off");
        }
        JsonNode row = store.updateReturning("""
                update report_submission t set status = 'APPROVED', approved_by = ?, approved_at = now(), updated_by = ?
                where t.id = ? returning row_to_json(t)::text""", TenantContext.principal(), TenantContext.principal(), id);
        ObjectNode payload = ((ObjectNode) row).deepCopy();
        if (comment != null) {
            payload.put("comment", comment);
        }
        outbox.publish("reporting.report_submission.approved", "report_submission", id, payload);
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "signed off directly");
        boolean auto = store.scalar("""
                select coalesce(s.auto_submit, false) from report_run r left join report_schedule s on s.id = r.report_schedule_id
                where r.id = ?""", Boolean.class, RunService.uuid(sub, "report_run_id")).orElse(false);
        return auto ? submit(id) : row.toString();
    }

    /** Transmits the signed-off file through the regulator gateway. */
    @Transactional
    public String submit(UUID id) {
        JsonNode sub = store.requireLocked("report_submission", id);
        Lifecycles.submission(sub.get("status").asString(), "SUBMITTED");
        JsonNode run = store.require("report_run", RunService.uuid(sub, "report_run_id"));
        JsonNode output = store.require("report_output", RunService.uuid(sub, "report_output_id"));
        if (output.get("revision").asInt() != run.path("revision").asInt(1) || run.hasNonNull("superseded_by_run_id")) {
            throw ApiException.conflict("output_outdated", "The run changed after sign-off (new revision or superseded); prepare a new submission");
        }
        String content = output.get("content").asString();
        if (!ReportRenderer.sha256(content).equals(output.get("sha256").asString())) {
            throw ApiException.conflict("output_integrity_failed", "Stored file does not match its checksum");
        }
        JsonNode def = store.require("report_definition", RunService.uuid(run, "report_definition_id"));
        RegulatorGateway.Receipt receipt = gateway.submit(new RegulatorGateway.Filing(def.get("regulator").asString(),
                def.get("jurisdiction").asString().trim(), sub.get("channel").asString(), def.get("code").asString(),
                output.get("file_name").asString(), output.get("content_type").asString(), content,
                output.get("sha256").asString()));
        if (!receipt.accepted()) {
            JsonNode row = store.updateReturning("""
                    update report_submission t set status = 'REJECTED', rejection_reason = ?, gateway_message = ?,
                           submitted_by = ?, updated_by = ? where t.id = ? returning row_to_json(t)::text""",
                    receipt.message(), receipt.message(), TenantContext.principal(), TenantContext.principal(), id);
            outbox.publish("reporting.report_submission.rejected", "report_submission", id, row);
            return row.toString();
        }
        JsonNode row = store.updateReturning("""
                update report_submission t set status = 'SUBMITTED', submitted_at = now(), submitted_by = ?, gateway_message = ?,
                       updated_by = ? where t.id = ? returning row_to_json(t)::text""",
                TenantContext.principal(), receipt.message(), TenantContext.principal(), id);
        ObjectNode payload = ((ObjectNode) row).deepCopy();
        payload.put("transmission_id", receipt.transmissionId());
        payload.put("sha256", output.get("sha256").asString());
        outbox.publish("reporting.report_submission.submitted", "report_submission", id, payload);
        return row.toString();
    }

    @Transactional
    public String acknowledge(UUID id, String reference, UUID documentId) {
        JsonNode sub = store.requireLocked("report_submission", id);
        Lifecycles.submission(sub.get("status").asString(), "ACKNOWLEDGED");
        JsonNode row = store.updateReturning("""
                update report_submission t set status = 'ACKNOWLEDGED', regulator_reference = ?, acknowledgement_document_id = ?,
                       acknowledged_at = now(), updated_by = ? where t.id = ? returning row_to_json(t)::text""",
                reference, documentId, TenantContext.principal(), id);
        outbox.publish("reporting.report_submission.acknowledged", "report_submission", id, row);
        return row.toString();
    }

    @Transactional
    public String recordRejection(UUID id, String reason) {
        JsonNode sub = store.requireLocked("report_submission", id);
        String st = sub.get("status").asString();
        if (!List.of("SUBMITTED", "ACKNOWLEDGED").contains(st)) {
            throw ApiException.conflict("invalid_transition", "Only submitted filings can be rejected by the regulator (submission is " + st + ")");
        }
        JsonNode row = store.updateReturning("""
                update report_submission t set status = 'REJECTED', rejection_reason = ?, updated_by = ?
                where t.id = ? returning row_to_json(t)::text""", reason, TenantContext.principal(), id);
        outbox.publish("reporting.report_submission.rejected", "report_submission", id, row);
        return row.toString();
    }

    /** Scheduled job body: deadline alerts for open submissions due within {@code days} of the business date. */
    @Transactional
    public void deadlineAlerts(LocalDate businessDate, int days, Map<String, Integer> counts) {
        List<JsonNode> due = store.rows("""
                select row_to_json(t)::text from report_submission t
                where t.status in ('PENDING_APPROVAL', 'APPROVED') and t.deadline_at is not null
                  and (t.deadline_at at time zone 'UTC')::date <= ?
                  and (t.deadline_alerted_on is null or t.deadline_alerted_on <> ?)
                order by t.deadline_at""", businessDate.plusDays(days), businessDate);
        int overdue = 0;
        for (JsonNode s : due) {
            LocalDate deadline = java.time.OffsetDateTime.parse(s.get("deadline_at").asString()).atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
            JsonNode row = store.updateReturning("""
                    update report_submission t set deadline_alerted_on = ?, updated_by = ? where t.id = ?
                    returning row_to_json(t)::text""", businessDate, TenantContext.principal(), RunService.uuid(s, "id"));
            ObjectNode payload = ((ObjectNode) row).deepCopy();
            long left = java.time.temporal.ChronoUnit.DAYS.between(businessDate, deadline);
            payload.put("days_to_deadline", left);
            payload.put("overdue", left < 0);
            if (left < 0) {
                overdue++;
            }
            outbox.publish("reporting.report_submission.deadline_approaching", "report_submission", RunService.uuid(s, "id"), payload);
        }
        counts.put("alerts", due.size());
        counts.put("overdue", overdue);
    }
}
