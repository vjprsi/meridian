package io.meridian.reconciliation.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.reconciliation.api.generated.ReconMatchesApi;
import io.meridian.reconciliation.api.generated.model.ReconMatch;
import io.meridian.reconciliation.api.generated.model.ReconMatchCreate;
import io.meridian.reconciliation.api.generated.model.ReconMatchesUnmatchRequest;
import io.meridian.reconciliation.application.Fields;
import io.meridian.reconciliation.application.MatchService;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Manual matches (confirmed by the caller) and unmatch via a compensating record. */
@RestController
public class ReconMatchesController implements ReconMatchesApi {
    private final MatchService matches;
    private final Idempotency idempotency;
    private final Responses responses;
    private final JsonMapper json;

    public ReconMatchesController(MatchService matches, Idempotency idempotency, Responses responses, JsonMapper json) {
        this.matches = matches;
        this.idempotency = idempotency;
        this.responses = responses;
        this.json = json;
    }

    @Override
    public ResponseEntity<ReconMatch> reconMatchesCreate(String idempotencyKey, ReconMatchCreate body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        return idempotency.execute(idempotencyKey, body, ReconMatch.class,
                () -> responses.created(matches.createManual(req), ReconMatch.class));
    }

    @Override
    public ResponseEntity<ReconMatch> reconMatchesUnmatch(UUID id, String idempotencyKey, ReconMatchesUnmatchRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        return idempotency.execute(idempotencyKey, responses.action("unmatch", id, req), ReconMatch.class,
                () -> responses.ok(matches.unmatch(id, Fields.text(req, "reason")), ReconMatch.class));
    }
}
