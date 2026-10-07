package io.meridian.identity.api;

import io.meridian.identity.api.generated.AccessReviewsApi;
import io.meridian.identity.api.generated.model.AccessReview;
import io.meridian.identity.api.generated.model.AccessReviewCreate;
import io.meridian.identity.api.generated.model.AccessReviewsCancelRequest;
import io.meridian.identity.api.generated.model.AccessReviewsDecisionsRequest;
import io.meridian.identity.api.generated.model.ApprovalRequest;
import io.meridian.identity.application.AccessReviewService;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * GAP-13 access recertification: the report (users x roles x last sign-in, privileged holders, dormant accounts) and
 * the periodic review (open with a snapshot, attest / revoke, submit for a second person's approval).
 */
@RestController
public class AccessReviewsController implements AccessReviewsApi {
    private final AccessReviewService reviews;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public AccessReviewsController(AccessReviewService reviews, CrudEngine crud, Idempotency idempotency,
                                   JsonMapper json) {
        this.reviews = reviews;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<Map<String, Object>> accessReviewsReport(Integer dormantDays) {
        return ResponseEntity.ok(Responses.object(json, reviews.report(dormantDays)));
    }

    @Override
    public ResponseEntity<AccessReview> accessReviewsCreate(String idempotencyKey, AccessReviewCreate body) {
        return idempotency.execute(idempotencyKey, body, AccessReview.class, () -> ResponseEntity.status(HttpStatus.CREATED)
                .body(crud.read(reviews.open(body), AccessReview.class)));
    }

    @Override
    public ResponseEntity<AccessReview> accessReviewsDecisions(UUID id, String idempotencyKey,
                                                               AccessReviewsDecisionsRequest body) {
        JsonNode req = body == null ? json.createObjectNode() : json.valueToTree(body);
        return idempotency.execute(idempotencyKey, Map.of("op", "decisions", "id", id, "body", req), AccessReview.class,
                () -> ResponseEntity.ok(crud.read(reviews.decide(id, req.get("decisions")), AccessReview.class)));
    }

    @Override
    public ResponseEntity<ApprovalRequest> accessReviewsSubmit(UUID id, String idempotencyKey) {
        return idempotency.execute(idempotencyKey, Map.of("op", "submit", "id", id), ApprovalRequest.class,
                () -> ResponseEntity.ok(json.treeToValue(reviews.submit(id, idempotencyKey), ApprovalRequest.class)));
    }

    @Override
    public ResponseEntity<AccessReview> accessReviewsCancel(UUID id, String idempotencyKey,
                                                            AccessReviewsCancelRequest body) {
        JsonNode req = body == null ? json.createObjectNode() : json.valueToTree(body);
        return idempotency.execute(idempotencyKey, Map.of("op", "cancel", "id", id, "body", req), AccessReview.class,
                () -> ResponseEntity.ok(crud.read(reviews.cancel(id, req.path("reason").asString(null)),
                        AccessReview.class)));
    }
}
