package io.meridian.payment.api;

import io.meridian.payment.api.generated.PaymentApprovalsApi;
import io.meridian.payment.api.generated.model.PaymentApproval;
import io.meridian.payment.api.generated.model.PaymentApprovalsApproveRequest;
import io.meridian.payment.api.generated.model.PaymentApprovalsRejectRequest;
import io.meridian.payment.application.ApprovalService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Approval levels of payments and bulk payments (four-eyes, in level order). */
@RestController
public class PaymentApprovalsController implements PaymentApprovalsApi {
    private final Actions actions;
    private final ApprovalService approvals;

    public PaymentApprovalsController(Actions actions, ApprovalService approvals) {
        this.actions = actions;
        this.approvals = approvals;
    }

    @Override
    public ResponseEntity<PaymentApproval> paymentApprovalsApprove(UUID id, String idempotencyKey,
                                                                  PaymentApprovalsApproveRequest body) {
        String comment = Actions.text(actions.tree(body), "comment");
        return actions.action(idempotencyKey, id, "approve", body, PaymentApproval.class, () -> approvals.decide(id, true, comment));
    }

    @Override
    public ResponseEntity<PaymentApproval> paymentApprovalsReject(UUID id, String idempotencyKey,
                                                                 PaymentApprovalsRejectRequest body) {
        String comment = Actions.text(actions.tree(body), "comment");
        return actions.action(idempotencyKey, id, "reject", body, PaymentApproval.class, () -> approvals.decide(id, false, comment));
    }
}
