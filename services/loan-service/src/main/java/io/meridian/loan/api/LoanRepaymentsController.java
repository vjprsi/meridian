package io.meridian.loan.api;

import io.meridian.loan.api.generated.LoanRepaymentsApi;
import io.meridian.loan.api.generated.model.LoanRepayment;
import io.meridian.loan.api.generated.model.LoanRepaymentsReverseRequest;
import io.meridian.loan.application.RepaymentService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Repayments (list/get via generic CRUD) and their reversal (returned direct debits, misapplied payments). */
@RestController
public class LoanRepaymentsController implements LoanRepaymentsApi {
    private final RepaymentService repayments;
    private final Actions actions;

    public LoanRepaymentsController(RepaymentService repayments, Actions actions) {
        this.repayments = repayments;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<LoanRepayment> loanRepaymentsReverse(UUID id, String idempotencyKey, LoanRepaymentsReverseRequest body) {
        return actions.action(idempotencyKey, id, "reverse", body, LoanRepayment.class,
                () -> repayments.reverse(id, Http.text(actions.tree(body), "reason")));
    }
}
