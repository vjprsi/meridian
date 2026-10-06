package io.meridian.loan.api;

import io.meridian.loan.api.generated.LoanRestructuresApi;
import io.meridian.loan.api.generated.model.LoanRestructure;
import io.meridian.loan.api.generated.model.LoanRestructuresApproveRequest;
import io.meridian.loan.api.generated.model.LoanRestructuresRejectRequest;
import io.meridian.loan.application.RestructureService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Restructure maker-checker: submit, approve (another user), reject, apply. */
@RestController
public class LoanRestructuresController implements LoanRestructuresApi {
    private final RestructureService restructures;
    private final Actions actions;

    public LoanRestructuresController(RestructureService restructures, Actions actions) {
        this.restructures = restructures;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<LoanRestructure> loanRestructuresSubmit(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "submit", null, LoanRestructure.class, () -> restructures.submit(id));
    }

    @Override
    public ResponseEntity<LoanRestructure> loanRestructuresApprove(UUID id, String idempotencyKey, LoanRestructuresApproveRequest body) {
        return actions.action(idempotencyKey, id, "approve", body, LoanRestructure.class,
                () -> restructures.approve(id, Http.text(actions.tree(body), "comment")));
    }

    @Override
    public ResponseEntity<LoanRestructure> loanRestructuresReject(UUID id, String idempotencyKey, LoanRestructuresRejectRequest body) {
        return actions.action(idempotencyKey, id, "reject", body, LoanRestructure.class,
                () -> restructures.reject(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<LoanRestructure> loanRestructuresApply(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "apply", null, LoanRestructure.class, () -> restructures.apply(id));
    }
}
