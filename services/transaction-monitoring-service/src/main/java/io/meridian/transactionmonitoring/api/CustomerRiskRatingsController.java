package io.meridian.transactionmonitoring.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.transactionmonitoring.api.generated.CustomerRiskRatingsApi;
import io.meridian.transactionmonitoring.api.generated.model.CustomerRiskRating;
import io.meridian.transactionmonitoring.api.generated.model.CustomerRiskRatingCreate;
import io.meridian.transactionmonitoring.api.generated.model.CustomerRiskRatingUpdate;
import io.meridian.transactionmonitoring.api.generated.model.CustomerRiskRatingsApproveOverrideRequest;
import io.meridian.transactionmonitoring.api.generated.model.CustomerRiskRatingsOverrideRequest;
import io.meridian.transactionmonitoring.api.generated.model.CustomerRiskRatingsRejectOverrideRequest;
import io.meridian.transactionmonitoring.application.RatingService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Customer AML risk ratings with four-eyes overrides. */
@RestController
public class CustomerRiskRatingsController implements CustomerRiskRatingsApi {
    private final RatingService ratings;
    private final Http http;

    public CustomerRiskRatingsController(RatingService ratings, Http http) {
        this.ratings = ratings;
        this.http = http;
    }

    @Override
    public ResponseEntity<CustomerRiskRating> customerRiskRatingsCreate(String idempotencyKey, CustomerRiskRatingCreate body) {
        return http.once(idempotencyKey, body, CustomerRiskRating.class,
                () -> http.respond(HttpStatus.CREATED, ratings.create(http.tree(body)), CustomerRiskRating.class));
    }

    @Override
    public ResponseEntity<CustomerRiskRating> customerRiskRatingsUpdate(UUID id, String ifMatch, CustomerRiskRatingUpdate body) {
        TenantContext.require();
        return http.ok(ratings.update(id, http.tree(body), Http.ifMatch(ifMatch)), CustomerRiskRating.class);
    }

    @Override
    public ResponseEntity<CustomerRiskRating> customerRiskRatingsRecalculate(UUID id, String idempotencyKey) {
        return http.once(idempotencyKey, null, CustomerRiskRating.class,
                () -> http.ok(ratings.recalculate(id), CustomerRiskRating.class));
    }

    @Override
    public ResponseEntity<CustomerRiskRating> customerRiskRatingsOverride(UUID id, String idempotencyKey,
                                                                          CustomerRiskRatingsOverrideRequest body) {
        JsonNode req = http.tree(body);
        return http.once(idempotencyKey, body, CustomerRiskRating.class, () -> http.ok(ratings.requestOverride(id,
                Http.text(req, "override_rating"), Http.text(req, "override_reason")), CustomerRiskRating.class));
    }

    @Override
    public ResponseEntity<CustomerRiskRating> customerRiskRatingsApproveOverride(UUID id, String idempotencyKey,
                                                                                 CustomerRiskRatingsApproveOverrideRequest body) {
        return http.once(idempotencyKey, body, CustomerRiskRating.class,
                () -> http.ok(ratings.approveOverride(id), CustomerRiskRating.class));
    }

    @Override
    public ResponseEntity<CustomerRiskRating> customerRiskRatingsRejectOverride(UUID id, String idempotencyKey,
                                                                                CustomerRiskRatingsRejectOverrideRequest body) {
        String reason = Http.text(http.tree(body), "reason");
        return http.once(idempotencyKey, body, CustomerRiskRating.class,
                () -> http.ok(ratings.rejectOverride(id, reason), CustomerRiskRating.class));
    }
}
