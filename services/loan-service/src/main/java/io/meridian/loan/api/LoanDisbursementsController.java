package io.meridian.loan.api;

import io.meridian.loan.api.generated.LoanDisbursementsApi;
import io.meridian.loan.api.generated.model.LoanDisbursement;
import io.meridian.loan.api.generated.model.LoanDisbursementsCancelRequest;
import io.meridian.loan.application.DisbursementService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Disbursements (list/get via generic CRUD): retry of failed or scheduled ones and cancellation. */
@RestController
public class LoanDisbursementsController implements LoanDisbursementsApi {
    private final DisbursementService disbursements;
    private final Actions actions;

    public LoanDisbursementsController(DisbursementService disbursements, Actions actions) {
        this.disbursements = disbursements;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<LoanDisbursement> loanDisbursementsRetry(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "retry", null, LoanDisbursement.class, () -> disbursements.retry(id));
    }

    @Override
    public ResponseEntity<LoanDisbursement> loanDisbursementsCancel(UUID id, String idempotencyKey, LoanDisbursementsCancelRequest body) {
        return actions.action(idempotencyKey, id, "cancel", body, LoanDisbursement.class,
                () -> disbursements.cancel(id, Http.text(actions.tree(body), "reason")));
    }
}
