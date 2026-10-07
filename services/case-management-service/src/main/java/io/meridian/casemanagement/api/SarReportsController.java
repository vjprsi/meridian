package io.meridian.casemanagement.api;

import io.meridian.casemanagement.api.generated.SarReportsApi;
import io.meridian.casemanagement.api.generated.model.SarReport;
import io.meridian.casemanagement.api.generated.model.SarReportCreate;
import io.meridian.casemanagement.api.generated.model.SarReportUpdate;
import io.meridian.casemanagement.api.generated.model.SarReportsAcknowledgeRequest;
import io.meridian.casemanagement.api.generated.model.SarReportsApproveRequest;
import io.meridian.casemanagement.api.generated.model.SarReportsReturnToDraftRequest;
import io.meridian.casemanagement.api.generated.model.SarReportsFileRequest;
import io.meridian.casemanagement.api.generated.model.SarReportsRecordRejectionRequest;
import io.meridian.casemanagement.api.generated.model.SarReportsSubmitForApprovalRequest;
import io.meridian.casemanagement.application.SarService;
import io.meridian.common.tenancy.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** SAR / STR drafting, approval, filing and goAML export. */
@RestController
public class SarReportsController implements SarReportsApi {
    private final SarService sars;
    private final Http http;

    public SarReportsController(SarService sars, Http http) {
        this.sars = sars;
        this.http = http;
    }

    @Override
    public ResponseEntity<SarReport> sarReportsCreate(String idempotencyKey, SarReportCreate body) {
        return http.once(idempotencyKey, body, SarReport.class,
                () -> http.respond(HttpStatus.CREATED, sars.create(http.tree(body)), SarReport.class));
    }

    @Override
    public ResponseEntity<SarReport> sarReportsUpdate(UUID id, String ifMatch, SarReportUpdate body) {
        TenantContext.require();
        return http.ok(sars.update(id, http.tree(body), Http.ifMatch(ifMatch)), SarReport.class);
    }

    @Override
    public ResponseEntity<Map<String, Object>> sarReportsDraftNarrative(UUID id, String idempotencyKey) {
        TenantContext.require();
        return ResponseEntity.ok(sars.draftNarrative(id));
    }

    @Override
    public ResponseEntity<SarReport> sarReportsSubmitForApproval(UUID id, String idempotencyKey, SarReportsSubmitForApprovalRequest body) {
        String comment = Http.text(http.tree(body), "comment");
        return http.once(idempotencyKey, body, SarReport.class, () -> http.ok(sars.submitForApproval(id, comment), SarReport.class));
    }

    @Override
    public ResponseEntity<SarReport> sarReportsApprove(UUID id, String idempotencyKey, SarReportsApproveRequest body) {
        String comment = Http.text(http.tree(body), "comment");
        return http.once(idempotencyKey, body, SarReport.class, () -> http.ok(sars.approve(id, comment), SarReport.class));
    }

    @Override
    public ResponseEntity<SarReport> sarReportsReturnToDraft(UUID id, String idempotencyKey, SarReportsReturnToDraftRequest body) {
        String reason = Http.text(http.tree(body), "reason");
        return http.once(idempotencyKey, body, SarReport.class, () -> http.ok(sars.returnToDraft(id, reason), SarReport.class));
    }

    @Override
    public ResponseEntity<SarReport> sarReportsFile(UUID id, String idempotencyKey, SarReportsFileRequest body) {
        String channel = Http.text(http.tree(body), "channel");
        return http.once(idempotencyKey, body, SarReport.class, () -> http.ok(sars.file(id, channel), SarReport.class));
    }

    @Override
    public ResponseEntity<SarReport> sarReportsAcknowledge(UUID id, String idempotencyKey, SarReportsAcknowledgeRequest body) {
        String ref = Http.text(http.tree(body), "regulator_reference");
        return http.once(idempotencyKey, body, SarReport.class, () -> http.ok(sars.acknowledge(id, ref), SarReport.class));
    }

    @Override
    public ResponseEntity<SarReport> sarReportsRecordRejection(UUID id, String idempotencyKey, SarReportsRecordRejectionRequest body) {
        String reason = Http.text(http.tree(body), "rejection_reason");
        return http.once(idempotencyKey, body, SarReport.class, () -> http.ok(sars.recordRejection(id, reason), SarReport.class));
    }

    @Override
    public ResponseEntity<String> sarReportsExport(UUID id) {
        TenantContext.require();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"goaml-" + id + ".xml\"")
                .body(sars.export(id));
    }
}
