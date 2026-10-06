package io.meridian.loanorigination.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.loanorigination.api.generated.ScorecardVersionsApi;
import io.meridian.loanorigination.api.generated.model.ScorecardVersion;
import io.meridian.loanorigination.api.generated.model.ScorecardVersionCreate;
import io.meridian.loanorigination.api.generated.model.ScorecardVersionUpdate;
import io.meridian.loanorigination.api.generated.model.ScorecardVersionsActivateRequest;
import io.meridian.loanorigination.api.generated.model.ScorecardVersionsSetChallengerRequest;
import io.meridian.loanorigination.api.generated.model.ScorecardVersionsTestRequest;
import io.meridian.loanorigination.application.ScorecardService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Scorecard versions: validated definitions, champion/challenger lifecycle and explainable test scoring. */
@RestController
public class ScorecardVersionsController implements ScorecardVersionsApi {
    private final ScorecardService scorecards;
    private final CrudEngine crud;
    private final Actions actions;

    public ScorecardVersionsController(ScorecardService scorecards, CrudEngine crud, Actions actions) {
        this.scorecards = scorecards;
        this.crud = crud;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<ScorecardVersion> scorecardVersionsCreate(UUID scorecardId, String idempotencyKey,
                                                                   ScorecardVersionCreate body) {
        return actions.create(idempotencyKey, Map.of("scorecard", scorecardId.toString(), "body", actions.tree(body)),
                ScorecardVersion.class, () -> scorecards.createVersion(scorecardId, body));
    }

    @Override
    public ResponseEntity<ScorecardVersion> scorecardVersionsUpdate(UUID scorecardId, UUID id, String ifMatch,
                                                                   ScorecardVersionUpdate body) {
        TenantContext.require();
        scorecards.checkUpdate(scorecardId, id, actions.tree(body));
        return crud.update("scorecard_version", ScorecardVersion.class, body, ifMatch,
                Crud.params().with("scorecard_id", scorecardId).with("id", id));
    }

    @Override
    public ResponseEntity<ScorecardVersion> scorecardVersionsActivate(UUID scorecardId, UUID id, String idempotencyKey,
                                                                     ScorecardVersionsActivateRequest body) {
        UUID approval = Http.uuid(actions.tree(body), "approval_request_id");
        return actions.action(idempotencyKey, id, "activate", body, ScorecardVersion.class,
                () -> scorecards.activate(scorecardId, id, approval));
    }

    @Override
    public ResponseEntity<ScorecardVersion> scorecardVersionsSetChallenger(UUID scorecardId, UUID id, String idempotencyKey,
                                                                          ScorecardVersionsSetChallengerRequest body) {
        java.math.BigDecimal pct = Http.decimal(actions.tree(body), "challenger_traffic_pct");
        return actions.action(idempotencyKey, id, "set-challenger", body, ScorecardVersion.class,
                () -> scorecards.setChallenger(scorecardId, id, pct));
    }

    @Override
    public ResponseEntity<ScorecardVersion> scorecardVersionsRetire(UUID scorecardId, UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "retire", null, ScorecardVersion.class, () -> scorecards.retire(scorecardId, id));
    }

    @Override
    public ResponseEntity<Map<String, Object>> scorecardVersionsTest(UUID scorecardId, UUID id, String idempotencyKey,
                                                                    ScorecardVersionsTestRequest body) {
        JsonNode inputs = actions.tree(body).get("inputs");
        return actions.objectAction(idempotencyKey, "test:" + id, body, () -> scorecards.test(scorecardId, id, inputs));
    }
}
