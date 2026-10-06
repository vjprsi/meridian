package io.meridian.productcatalog.api;

import io.meridian.productcatalog.api.generated.FeeTiersApi;
import io.meridian.productcatalog.api.generated.model.FeeTier;
import io.meridian.productcatalog.api.generated.model.FeeTierCreate;
import io.meridian.productcatalog.api.generated.model.FeeTierUpdate;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tiers of a tiered fee. Writes are only allowed while the owning version is DRAFT (409 version_not_editable otherwise) and are
 * validated against product rules (422 invalid_configuration).
 */
@RestController
public class FeeTiersController implements FeeTiersApi {
    private final ContentEndpoints content;

    public FeeTiersController(ContentEndpoints content) {
        this.content = content;
    }

    @Override
    public ResponseEntity<FeeTier> feeTiersCreate(String idempotencyKey, FeeTierCreate body) {
        return content.create("fee_tier", FeeTier.class, idempotencyKey, body);
    }

    @Override
    public ResponseEntity<FeeTier> feeTiersUpdate(UUID id, String ifMatch, FeeTierUpdate body) {
        return content.update("fee_tier", FeeTier.class, id, ifMatch, body);
    }

    @Override
    public ResponseEntity<Void> feeTiersDelete(UUID id) {
        return content.delete("fee_tier", id);
    }
}
