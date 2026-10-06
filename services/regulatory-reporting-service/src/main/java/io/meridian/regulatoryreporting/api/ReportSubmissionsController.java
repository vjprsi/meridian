package io.meridian.regulatoryreporting.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.regulatoryreporting.api.generated.ReportSubmissionsApi;
import io.meridian.regulatoryreporting.api.generated.model.ReportSubmission;
import io.meridian.regulatoryreporting.api.generated.model.ReportSubmissionCreate;
import io.meridian.regulatoryreporting.api.generated.model.ReportSubmissionsAcknowledgeRequest;
import io.meridian.regulatoryreporting.api.generated.model.ReportSubmissionsApproveRequest;
import io.meridian.regulatoryreporting.api.generated.model.ReportSubmissionsRecordRejectionRequest;
import io.meridian.regulatoryreporting.application.Fields;
import io.meridian.regulatoryreporting.application.ScheduledJobs;
import io.meridian.regulatoryreporting.application.SubmissionService;
import io.meridian.web.idempotency.Idempotency;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Regulator submissions: prepare, sign off (four eyes), submit, acknowledgement / rejection, deadline alerts. */
@RestController
public class ReportSubmissionsController implements ReportSubmissionsApi {
    private final SubmissionService submissions;
    private final ScheduledJobs jobs;
    private final Idempotency idempotency;
    private final Responses responses;
    private final JsonMapper json;

    public ReportSubmissionsController(SubmissionService submissions, ScheduledJobs jobs, Idempotency idempotency,
                                       Responses responses, JsonMapper json) {
        this.submissions = submissions;
        this.jobs = jobs;
        this.idempotency = idempotency;
        this.responses = responses;
        this.json = json;
    }

    @Override
    public ResponseEntity<ReportSubmission> reportSubmissionsCreate(String idempotencyKey, ReportSubmissionCreate body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        return idempotency.execute(idempotencyKey, body, ReportSubmission.class,
                () -> responses.created(submissions.create(req), ReportSubmission.class));
    }

    @Override
    public ResponseEntity<ReportSubmission> reportSubmissionsApprove(UUID id, String idempotencyKey,
                                                                    ReportSubmissionsApproveRequest body) {
        TenantContext.require();
        JsonNode req = body == null ? json.createObjectNode() : json.valueToTree(body);
        return idempotency.execute(idempotencyKey, responses.action("approve", id, req), ReportSubmission.class,
                () -> responses.ok(submissions.approve(id, Fields.text(req, "comment")), ReportSubmission.class));
    }

    @Override
    public ResponseEntity<ReportSubmission> reportSubmissionsSubmit(UUID id, String idempotencyKey) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, responses.action("submit", id, null), ReportSubmission.class,
                () -> responses.ok(submissions.submit(id), ReportSubmission.class));
    }

    @Override
    public ResponseEntity<ReportSubmission> reportSubmissionsAcknowledge(UUID id, String idempotencyKey,
                                                                        ReportSubmissionsAcknowledgeRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        String ref = Fields.requireText(req, "regulator_reference");
        UUID doc = Fields.uuid(req, "acknowledgement_document_id");
        return idempotency.execute(idempotencyKey, responses.action("acknowledge", id, req), ReportSubmission.class,
                () -> responses.ok(submissions.acknowledge(id, ref, doc), ReportSubmission.class));
    }

    @Override
    public ResponseEntity<ReportSubmission> reportSubmissionsRecordRejection(UUID id, String idempotencyKey,
                                                                            ReportSubmissionsRecordRejectionRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        String reason = Fields.requireText(req, "rejection_reason");
        return idempotency.execute(idempotencyKey, responses.action("record-rejection", id, req), ReportSubmission.class,
                () -> responses.ok(submissions.recordRejection(id, reason), ReportSubmission.class));
    }

    @Override
    public ResponseEntity<Map<String, Object>> reportSubmissionsDeadlineAlerts(LocalDate businessDate, String idempotencyKey,
                                                                              Integer days) {
        TenantContext.require();
        int window = days == null ? 3 : days;
        if (window < 0 || window > 90) {
            throw Fields.invalid("days", "out_of_range", "days must be 0-90");
        }
        JsonNode fingerprint = json.createObjectNode().put("job", "DEADLINE_ALERTS")
                .put("business_date", String.valueOf(businessDate)).put("days", window);
        return responses.object(idempotency.execute(idempotencyKey, fingerprint, JsonNode.class,
                () -> ResponseEntity.ok(jobs.run("DEADLINE_ALERTS", businessDate,
                        (counts, errors) -> submissions.deadlineAlerts(businessDate, window, counts)))));
    }
}
