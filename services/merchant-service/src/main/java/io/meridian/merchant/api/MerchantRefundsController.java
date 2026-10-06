package io.meridian.merchant.api;

import io.meridian.merchant.api.generated.MerchantRefundsApi;
import io.meridian.merchant.api.generated.model.MerchantRefund;
import io.meridian.merchant.api.generated.model.MerchantRefundCreate;
import io.meridian.merchant.api.generated.model.MerchantRefundsApproveRequest;
import io.meridian.merchant.api.generated.model.MerchantRefundsRejectRequest;
import io.meridian.merchant.application.RefundService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Merchant refunds with maker-checker approval. */
@RestController
public class MerchantRefundsController implements MerchantRefundsApi {
    private final RefundService refunds;
    private final Actions actions;

    public MerchantRefundsController(RefundService refunds, Actions actions) {
        this.refunds = refunds;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<MerchantRefund> merchantRefundsCreate(String idempotencyKey, MerchantRefundCreate body) {
        return actions.create(idempotencyKey, body, MerchantRefund.class, () -> refunds.create(actions.tree(body), idempotencyKey));
    }

    @Override
    public ResponseEntity<MerchantRefund> merchantRefundsApprove(UUID id, String idempotencyKey, MerchantRefundsApproveRequest body) {
        return actions.action(idempotencyKey, id, "approve", body, MerchantRefund.class,
                () -> refunds.approve(id, Http.text(actions.tree(body), "comment")));
    }

    @Override
    public ResponseEntity<MerchantRefund> merchantRefundsReject(UUID id, String idempotencyKey, MerchantRefundsRejectRequest body) {
        return actions.action(idempotencyKey, id, "reject", body, MerchantRefund.class,
                () -> refunds.reject(id, Http.text(actions.tree(body), "reason")));
    }
}
