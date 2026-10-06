package io.meridian.payment.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.payment.api.generated.BulkPaymentsApi;
import io.meridian.payment.api.generated.model.BulkPayment;
import io.meridian.payment.api.generated.model.BulkPaymentCreate;
import io.meridian.payment.api.generated.model.BulkPaymentItemPage;
import io.meridian.payment.api.generated.model.BulkPaymentsApproveRequest;
import io.meridian.payment.api.generated.model.BulkPaymentsCancelRequest;
import io.meridian.payment.api.generated.model.BulkPaymentsRejectRequest;
import io.meridian.payment.api.generated.model.BulkPaymentsUploadRequest;
import io.meridian.payment.application.ApprovalService;
import io.meridian.payment.application.BulkService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Bulk payments: JSON or file upload, validation, maker-checker submission and approval, execution report. */
@RestController
public class BulkPaymentsController implements BulkPaymentsApi {
    private final Actions actions;
    private final BulkService bulk;
    private final ApprovalService approvals;

    public BulkPaymentsController(Actions actions, BulkService bulk, ApprovalService approvals) {
        this.actions = actions;
        this.bulk = bulk;
        this.approvals = approvals;
    }

    @Override
    public ResponseEntity<BulkPayment> bulkPaymentsCreate(String idempotencyKey, BulkPaymentCreate body) {
        return actions.create(idempotencyKey, body, BulkPayment.class, () -> bulk.create(actions.tree(body)));
    }

    @Override
    public ResponseEntity<BulkPayment> bulkPaymentsUpload(String idempotencyKey, BulkPaymentsUploadRequest body) {
        return actions.create(idempotencyKey, body, BulkPayment.class, () -> bulk.upload(actions.tree(body)));
    }

    @Override
    public ResponseEntity<BulkPaymentItemPage> bulkPaymentsItems(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        bulk.report(id); // 404 when unknown
        return actions.page("bulk_payment_item", "bulk_payment_id", id, limit, cursor, BulkPaymentItemPage.class);
    }

    @Override
    public ResponseEntity<BulkPayment> bulkPaymentsValidate(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "validate", null, BulkPayment.class, () -> bulk.validate(id));
    }

    @Override
    public ResponseEntity<BulkPayment> bulkPaymentsSubmit(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "submit", null, BulkPayment.class, () -> bulk.submit(id));
    }

    @Override
    public ResponseEntity<BulkPayment> bulkPaymentsApprove(UUID id, String idempotencyKey, BulkPaymentsApproveRequest body) {
        String comment = Actions.text(actions.tree(body), "comment");
        return actions.action(idempotencyKey, id, "approve", body, BulkPayment.class, () -> approvals.approveBulk(id, comment));
    }

    @Override
    public ResponseEntity<BulkPayment> bulkPaymentsReject(UUID id, String idempotencyKey, BulkPaymentsRejectRequest body) {
        String reason = Actions.text(actions.tree(body), "reason");
        return actions.action(idempotencyKey, id, "reject", body, BulkPayment.class, () -> bulk.reject(id, reason));
    }

    @Override
    public ResponseEntity<BulkPayment> bulkPaymentsCancel(UUID id, String idempotencyKey, BulkPaymentsCancelRequest body) {
        String reason = Actions.text(actions.tree(body), "reason");
        return actions.action(idempotencyKey, id, "cancel", body, BulkPayment.class, () -> bulk.cancel(id, reason));
    }

    @Override
    public ResponseEntity<String> bulkPaymentsReport(UUID id) {
        TenantContext.require();
        String csv = bulk.report(id);
        return actions.raw(csv.getBytes(StandardCharsets.UTF_8), "text/csv;charset=UTF-8", "bulk-" + id + "-report.csv");
    }
}
