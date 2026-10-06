package io.meridian.kyc.api;

import io.meridian.common.error.ApiException;
import io.meridian.kyc.api.generated.KycReviewsApi;
import io.meridian.kyc.api.generated.model.KycReview;
import io.meridian.kyc.api.generated.model.KycReviewCreate;
import io.meridian.kyc.api.generated.model.KycReviewUpdate;
import io.meridian.kyc.api.generated.model.KycReviewsAssignRequest;
import io.meridian.kyc.api.generated.model.KycReviewsCompleteRequest;
import io.meridian.kyc.api.generated.model.KycReviewsRequestUpdateRequest;
import io.meridian.kyc.application.KycSupport;
import io.meridian.kyc.application.ReviewService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** KYC reviews: scheduling, assignment, outreach, completion. */
@RestController
public class KycReviewsController implements KycReviewsApi {
    private final ReviewService reviews;
    private final Actions actions;
    private final CrudEngine crud;
    private final KycSupport kyc;

    public KycReviewsController(ReviewService reviews, Actions actions, CrudEngine crud, KycSupport kyc) {
        this.reviews = reviews;
        this.actions = actions;
        this.crud = crud;
        this.kyc = kyc;
    }

    @Override
    public ResponseEntity<KycReview> kycReviewsCreate(String idempotencyKey, KycReviewCreate body) {
        return actions.created(idempotencyKey, "review.create", body, KycReview.class, () -> reviews.create(body));
    }

    @Override
    public ResponseEntity<KycReview> kycReviewsUpdate(UUID id, String ifMatch, KycReviewUpdate body) {
        reviews.validateUpdate(id, body);
        return crud.update("kyc_review", KycReview.class, body, ifMatch, Crud.params().with("id", id));
    }

    @Override
    public ResponseEntity<KycReview> kycReviewsAssign(UUID id, String idempotencyKey, KycReviewsAssignRequest body) {
        UUID analyst = KycSupport.uuid(kyc.tree(body), "assigned_to");
        if (analyst == null) {
            throw ApiException.unprocessable("assigned_to_required", "assigned_to is required");
        }
        return actions.ok(idempotencyKey, "review.assign", id, body, KycReview.class, () -> reviews.assign(id, analyst));
    }

    @Override
    public ResponseEntity<KycReview> kycReviewsRequestUpdate(UUID id, String idempotencyKey,
                                                             KycReviewsRequestUpdateRequest body) {
        String message = KycSupport.text(kyc.tree(body), "message");
        return actions.ok(idempotencyKey, "review.request-update", id, body, KycReview.class,
                () -> reviews.requestUpdate(id, message));
    }

    @Override
    public ResponseEntity<KycReview> kycReviewsComplete(UUID id, String idempotencyKey, KycReviewsCompleteRequest body) {
        JsonNode req = kyc.tree(body);
        return actions.ok(idempotencyKey, "review.complete", id, body, KycReview.class,
                () -> reviews.complete(id, KycSupport.text(req, "outcome"), KycSupport.text(req, "outcome_notes")));
    }
}
