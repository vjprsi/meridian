package io.meridian.loan.api;

import io.meridian.loan.api.generated.LoanRecoveriesApi;
import io.meridian.loan.api.generated.model.LoanRecovery;
import io.meridian.loan.api.generated.model.LoanRecoveryCreate;
import io.meridian.loan.application.WriteOffService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Post-write-off recoveries (customer, agency, collateral sale, insurance, debt sale, legal). */
@RestController
public class LoanRecoveriesController implements LoanRecoveriesApi {
    private final WriteOffService writeOffs;
    private final Actions actions;

    public LoanRecoveriesController(WriteOffService writeOffs, Actions actions) {
        this.writeOffs = writeOffs;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<LoanRecovery> loanRecoveriesCreate(String idempotencyKey, LoanRecoveryCreate body) {
        return actions.create(idempotencyKey, body, LoanRecovery.class, () -> writeOffs.recovery(actions.tree(body)));
    }
}
