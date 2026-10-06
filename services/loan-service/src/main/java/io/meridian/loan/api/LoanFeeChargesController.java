package io.meridian.loan.api;

import io.meridian.loan.api.generated.LoanFeeChargesApi;
import io.meridian.loan.api.generated.model.LoanFeeCharge;
import io.meridian.loan.api.generated.model.LoanFeeChargeCreate;
import io.meridian.loan.api.generated.model.LoanFeeChargesWaiveRequest;
import io.meridian.loan.application.FeeService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Manual fee charges (posted to the ledger) and fee waivers. */
@RestController
public class LoanFeeChargesController implements LoanFeeChargesApi {
    private final FeeService fees;
    private final Actions actions;

    public LoanFeeChargesController(FeeService fees, Actions actions) {
        this.fees = fees;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<LoanFeeCharge> loanFeeChargesCreate(String idempotencyKey, LoanFeeChargeCreate body) {
        return actions.create(idempotencyKey, body, LoanFeeCharge.class, () -> fees.create(actions.tree(body), idempotencyKey));
    }

    @Override
    public ResponseEntity<LoanFeeCharge> loanFeeChargesWaive(UUID id, String idempotencyKey, LoanFeeChargesWaiveRequest body) {
        return actions.action(idempotencyKey, id, "waive", body, LoanFeeCharge.class,
                () -> fees.waive(id, Http.text(actions.tree(body), "waiver_reason")));
    }
}
