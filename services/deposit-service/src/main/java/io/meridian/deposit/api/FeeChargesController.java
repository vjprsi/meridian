package io.meridian.deposit.api;

import io.meridian.deposit.api.generated.FeeChargesApi;
import io.meridian.deposit.api.generated.model.FeeCharge;
import io.meridian.deposit.api.generated.model.FeeChargesRefundRequest;
import io.meridian.deposit.application.FeeService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Fee charges: refunds (full = ledger reversal, partial = compensating posting). */
@RestController
public class FeeChargesController implements FeeChargesApi {
    private final FeeService fees;
    private final Actions actions;

    public FeeChargesController(FeeService fees, Actions actions) {
        this.fees = fees;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<FeeCharge> feeChargesRefund(UUID id, String idempotencyKey, FeeChargesRefundRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "refund", body, FeeCharge.class,
                () -> fees.refund(id, Http.decimal(req, "amount"), Http.text(req, "reason"), idempotencyKey));
    }
}
