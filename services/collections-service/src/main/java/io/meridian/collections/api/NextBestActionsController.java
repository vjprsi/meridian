package io.meridian.collections.api;

import io.meridian.collections.api.generated.NextBestActionsApi;
import io.meridian.collections.api.generated.model.NextBestAction;
import io.meridian.collections.api.generated.model.NextBestActionsRejectRequest;
import io.meridian.collections.application.NextBestActionService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Next-best-action suggestions: accept (schedules the case's next action) or reject with a reason. */
@RestController
public class NextBestActionsController implements NextBestActionsApi {
    private final NextBestActionService nba;
    private final Actions actions;

    public NextBestActionsController(NextBestActionService nba, Actions actions) {
        this.nba = nba;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<NextBestAction> nextBestActionsAccept(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "accept", null, NextBestAction.class, () -> nba.accept(id));
    }

    @Override
    public ResponseEntity<NextBestAction> nextBestActionsReject(UUID id, String idempotencyKey,
                                                               NextBestActionsRejectRequest body) {
        return actions.action(idempotencyKey, id, "reject", body, NextBestAction.class,
                () -> nba.reject(id, Http.text(actions.tree(body), "reason")));
    }
}
