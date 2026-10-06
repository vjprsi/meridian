package io.meridian.productcatalog.api;

import io.meridian.productcatalog.api.generated.InterestRateTiersApi;
import io.meridian.productcatalog.api.generated.model.InterestRateTier;
import io.meridian.productcatalog.api.generated.model.InterestRateTierCreate;
import io.meridian.productcatalog.api.generated.model.InterestRateTierUpdate;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tiers/bands/steps of an interest-rate configuration. Writes are only allowed while the owning version is DRAFT (409 version_not_editable otherwise) and are
 * validated against product rules (422 invalid_configuration).
 */
@RestController
public class InterestRateTiersController implements InterestRateTiersApi {
    private final ContentEndpoints content;

    public InterestRateTiersController(ContentEndpoints content) {
        this.content = content;
    }

    @Override
    public ResponseEntity<InterestRateTier> interestRateTiersCreate(String idempotencyKey, InterestRateTierCreate body) {
        return content.create("interest_rate_tier", InterestRateTier.class, idempotencyKey, body);
    }

    @Override
    public ResponseEntity<InterestRateTier> interestRateTiersUpdate(UUID id, String ifMatch, InterestRateTierUpdate body) {
        return content.update("interest_rate_tier", InterestRateTier.class, id, ifMatch, body);
    }

    @Override
    public ResponseEntity<Void> interestRateTiersDelete(UUID id) {
        return content.delete("interest_rate_tier", id);
    }
}
