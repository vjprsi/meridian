package io.meridian.disbursement.api;

import io.meridian.disbursement.api.generated.DisbursementBatchesApi;
import io.meridian.disbursement.api.generated.model.DisbursementBatch;
import io.meridian.disbursement.api.generated.model.DisbursementBatchApprovalPage;
import io.meridian.disbursement.api.generated.model.DisbursementBatchCreate;
import io.meridian.disbursement.api.generated.model.DisbursementBatchesApproveRequest;
import io.meridian.disbursement.api.generated.model.DisbursementBatchesCancelRequest;
import io.meridian.disbursement.api.generated.model.DisbursementBatchesRejectRequest;
import io.meridian.disbursement.api.generated.model.DisbursementBatchesUploadRequest;
import io.meridian.disbursement.api.generated.model.DisbursementBeneficiaryPage;
import io.meridian.disbursement.application.BatchService;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Disbursement batches: upload, validation, maker-checker approval, execution, retries and the report. */
@RestController
public class DisbursementBatchesController implements DisbursementBatchesApi {
    private final BatchService batches;
    private final Actions actions;
    private final CrudEngine crud;

    public DisbursementBatchesController(BatchService batches, Actions actions, CrudEngine crud) {
        this.batches = batches;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<DisbursementBatch> disbursementBatchesCreate(String idempotencyKey, DisbursementBatchCreate body) {
        return actions.create(idempotencyKey, body, DisbursementBatch.class, () -> batches.create(actions.tree(body)));
    }

    @Override
    public ResponseEntity<DisbursementBatch> disbursementBatchesUpload(UUID id, String idempotencyKey,
                                                                       DisbursementBatchesUploadRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "upload", body, DisbursementBatch.class,
                () -> batches.upload(id, Http.text(req, "content"), Http.text(req, "file_name"), Http.uuid(req, "file_document_id")));
    }

    @Override
    public ResponseEntity<DisbursementBatch> disbursementBatchesValidate(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "validate", null, DisbursementBatch.class, () -> batches.validate(id));
    }

    @Override
    public ResponseEntity<DisbursementBatch> disbursementBatchesSubmit(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "submit", null, DisbursementBatch.class, () -> batches.submit(id));
    }

    @Override
    public ResponseEntity<DisbursementBatch> disbursementBatchesApprove(UUID id, String idempotencyKey,
                                                                        DisbursementBatchesApproveRequest body) {
        return actions.action(idempotencyKey, id, "approve", body, DisbursementBatch.class,
                () -> batches.approve(id, Http.text(actions.tree(body), "comment")));
    }

    @Override
    public ResponseEntity<DisbursementBatch> disbursementBatchesReject(UUID id, String idempotencyKey,
                                                                       DisbursementBatchesRejectRequest body) {
        return actions.action(idempotencyKey, id, "reject", body, DisbursementBatch.class,
                () -> batches.reject(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<DisbursementBatch> disbursementBatchesCancel(UUID id, String idempotencyKey,
                                                                       DisbursementBatchesCancelRequest body) {
        return actions.action(idempotencyKey, id, "cancel", body, DisbursementBatch.class,
                () -> batches.cancel(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<DisbursementBatch> disbursementBatchesRetryFailed(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "retry-failed", null, DisbursementBatch.class, () -> batches.retryFailed(id));
    }

    @Override
    public ResponseEntity<DisbursementBatchApprovalPage> disbursementBatchesApprovals(UUID id, Integer limit, String cursor) {
        crud.get("disbursement_batch", DisbursementBatch.class, Crud.params().with("id", id));
        return crud.list("disbursement_batch_approval", DisbursementBatchApprovalPage.class,
                Crud.params().with("disbursement_batch_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<DisbursementBeneficiaryPage> disbursementBatchesBeneficiaries(UUID id, String validationStatus,
                                                                                        String payoutStatus, Integer limit,
                                                                                        String cursor) {
        crud.get("disbursement_batch", DisbursementBatch.class, Crud.params().with("id", id));
        return crud.list("disbursement_beneficiary", DisbursementBeneficiaryPage.class,
                Crud.params().with("disbursement_batch_id", id).with("validation_status", validationStatus)
                        .with("payout_status", payoutStatus).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<String> disbursementBatchesReport(UUID id) {
        TenantContext.require();
        String csv = batches.report(id);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"disbursement-batch-" + id + ".csv\"")
                .body(csv);
    }
}
