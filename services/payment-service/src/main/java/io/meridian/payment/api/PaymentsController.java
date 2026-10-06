package io.meridian.payment.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.payment.api.generated.PaymentsApi;
import io.meridian.payment.api.generated.model.Payment;
import io.meridian.payment.api.generated.model.PaymentApprovalPage;
import io.meridian.payment.api.generated.model.PaymentCreate;
import io.meridian.payment.api.generated.model.PaymentRecall;
import io.meridian.payment.api.generated.model.PaymentReturn;
import io.meridian.payment.api.generated.model.PaymentStatusHistoryPage;
import io.meridian.payment.api.generated.model.PaymentsApproveRequest;
import io.meridian.payment.api.generated.model.PaymentsCancelRequest;
import io.meridian.payment.api.generated.model.PaymentsInboundRequest;
import io.meridian.payment.api.generated.model.PaymentsRecallRequest;
import io.meridian.payment.api.generated.model.PaymentsRefundRequest;
import io.meridian.payment.api.generated.model.PaymentsRejectRequest;
import io.meridian.payment.api.generated.model.PaymentsReturnRequest;
import io.meridian.payment.api.generated.model.PaymentsScreeningDecisionRequest;
import io.meridian.payment.api.generated.model.PaymentsStatusReportRequest;
import io.meridian.payment.api.generated.model.PaymentsValidateRequest;
import io.meridian.payment.application.ApprovalService;
import io.meridian.payment.application.PaymentProcessor;
import io.meridian.payment.application.PaymentQueries;
import io.meridian.payment.application.ReturnService;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Payments: initiation through the full lifecycle (screening, limits, approvals, funds hold, routing, submission,
 * settlement), rail status reports, inbound credits, returns, recalls, refunds, tracker and receipts.
 * Contract: generated/openapi/services/payment-service.openapi.yaml
 */
@RestController
public class PaymentsController implements PaymentsApi {
    private final Actions actions;
    private final PaymentProcessor processor;
    private final ApprovalService approvals;
    private final ReturnService returns;
    private final PaymentQueries queries;

    public PaymentsController(Actions actions, PaymentProcessor processor, ApprovalService approvals,
                              ReturnService returns, PaymentQueries queries) {
        this.actions = actions;
        this.processor = processor;
        this.approvals = approvals;
        this.returns = returns;
        this.queries = queries;
    }

    @Override
    public ResponseEntity<Payment> paymentsCreate(String idempotencyKey, PaymentCreate body) {
        return actions.create(idempotencyKey, body, Payment.class,
                () -> processor.initiate((ObjectNode) actions.tree(body), idempotencyKey, PaymentProcessor.Options.DEFAULT));
    }

    @Override
    public ResponseEntity<Map<String, Object>> paymentsValidate(String idempotencyKey, PaymentsValidateRequest body) {
        JsonNode req = actions.tree(body);
        return actions.object(idempotencyKey, null, "validate", body, () -> queries.validate(req.get("payment")));
    }

    @Override
    public ResponseEntity<Payment> paymentsInbound(String idempotencyKey, PaymentsInboundRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, null, "inbound", body, Payment.class, () -> processor.receive(req, idempotencyKey));
    }

    @Override
    public ResponseEntity<Map<String, Object>> paymentsExecuteDue(String idempotencyKey) {
        return actions.object(idempotencyKey, null, "execute-due", null, queries::executeDue);
    }

    @Override
    public ResponseEntity<Payment> paymentsApprove(UUID id, String idempotencyKey, PaymentsApproveRequest body) {
        String comment = Actions.text(actions.tree(body), "comment");
        return actions.action(idempotencyKey, id, "approve", body, Payment.class, () -> approvals.approvePayment(id, comment));
    }

    @Override
    public ResponseEntity<Payment> paymentsReject(UUID id, String idempotencyKey, PaymentsRejectRequest body) {
        String reason = Actions.text(actions.tree(body), "reason");
        return actions.action(idempotencyKey, id, "reject", body, Payment.class, () -> approvals.rejectPayment(id, reason));
    }

    @Override
    public ResponseEntity<Payment> paymentsCancel(UUID id, String idempotencyKey, PaymentsCancelRequest body) {
        String reason = Actions.text(actions.tree(body), "reason");
        return actions.action(idempotencyKey, id, "cancel", body, Payment.class, () -> processor.cancel(id, reason));
    }

    @Override
    public ResponseEntity<Payment> paymentsExecute(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "execute", null, Payment.class,
                () -> processor.execute(id, PaymentProcessor.Options.DEFAULT));
    }

    @Override
    public ResponseEntity<Payment> paymentsScreeningDecision(UUID id, String idempotencyKey,
                                                            PaymentsScreeningDecisionRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "screening-decision", body, Payment.class,
                () -> processor.screeningDecision(id, Actions.text(req, "status"), Actions.uuid(req, "screening_result_id"),
                        Actions.text(req, "reason")));
    }

    @Override
    public ResponseEntity<Payment> paymentsStatusReport(UUID id, String idempotencyKey, PaymentsStatusReportRequest body) {
        JsonNode req = actions.tree(body);
        String date = Actions.text(req, "settlement_date");
        return actions.action(idempotencyKey, id, "status-report", body, Payment.class,
                () -> processor.statusReport(id, Actions.text(req, "status"), Actions.text(req, "reason_code"),
                        Actions.text(req, "reason"), Actions.text(req, "clearing_system_ref"),
                        Actions.uuid(req, "rail_message_id"), date == null ? null : LocalDate.parse(date)));
    }

    @Override
    public ResponseEntity<PaymentReturn> paymentsReturn(UUID id, String idempotencyKey, PaymentsReturnRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "return", body, PaymentReturn.class,
                () -> returns.returnInbound(id, Actions.text(req, "reason_code"), Actions.text(req, "reason"),
                        Actions.decimal(req, "amount"), null));
    }

    @Override
    public ResponseEntity<PaymentRecall> paymentsRecall(UUID id, String idempotencyKey, PaymentsRecallRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "recall", body, PaymentRecall.class,
                () -> returns.recall(id, Actions.text(req, "reason_code"), Actions.text(req, "reason")));
    }

    @Override
    public ResponseEntity<Payment> paymentsRefund(UUID id, String idempotencyKey, PaymentsRefundRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "refund", body, Payment.class,
                () -> returns.refund(id, Actions.decimal(req, "amount"), Actions.text(req, "reason"), idempotencyKey));
    }

    @Override
    public ResponseEntity<PaymentStatusHistoryPage> paymentsStatusHistory(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        queries.payment(id); // 404 for unknown payments
        return actions.page("payment_status_history", "payment_id", id, limit, cursor, PaymentStatusHistoryPage.class);
    }

    @Override
    public ResponseEntity<PaymentApprovalPage> paymentsApprovals(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        queries.payment(id);
        return actions.page("payment_approval", "payment_id", id, limit, cursor, PaymentApprovalPage.class);
    }

    @Override
    public ResponseEntity<Map<String, Object>> paymentsTracker(UUID id) {
        return actions.read(queries.tracker(id));
    }

    @Override
    public ResponseEntity<org.springframework.core.io.Resource> paymentsReceipt(UUID id) {
        TenantContext.require();
        byte[] pdf = queries.receipt(id);
        return actions.raw(pdf, "application/pdf", "payment-" + id + ".pdf");
    }
}
