package io.meridian.loanorigination.api;

import io.meridian.loanorigination.api.generated.CreditDecisionRequestsApi;
import io.meridian.loanorigination.api.generated.model.CreditDecision;
import io.meridian.loanorigination.api.generated.model.CreditDecisionRequest;
import io.meridian.loanorigination.api.generated.model.CreditDecisionRequestsApproveRequest;
import io.meridian.loanorigination.api.generated.model.CreditDecisionRequestsRejectRequest;
import io.meridian.loanorigination.application.DecisionService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Maker-checker review of manual credit decisions. */
@RestController
public class CreditDecisionRequestsController implements CreditDecisionRequestsApi {
    private final DecisionService decisions;
    private final Actions actions;

    public CreditDecisionRequestsController(DecisionService decisions, Actions actions) {
        this.decisions = decisions;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<CreditDecision> creditDecisionRequestsApprove(UUID id, String idempotencyKey,
                                                                       CreditDecisionRequestsApproveRequest body) {
        String comment = Http.text(actions.tree(body), "comment");
        return actions.action(idempotencyKey, id, "approve", body, CreditDecision.class, () -> decisions.approve(id, comment));
    }

    @Override
    public ResponseEntity<CreditDecisionRequest> creditDecisionRequestsReject(UUID id, String idempotencyKey,
                                                                             CreditDecisionRequestsRejectRequest body) {
        String reason = Http.text(actions.tree(body), "reason");
        return actions.action(idempotencyKey, id, "reject", body, CreditDecisionRequest.class, () -> decisions.reject(id, reason));
    }
}
