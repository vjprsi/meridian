package io.meridian.collateral.api;

import io.meridian.collateral.api.generated.CollateralValuationsApi;
import io.meridian.collateral.api.generated.model.CollateralValuation;
import io.meridian.collateral.api.generated.model.CollateralValuationCreate;
import io.meridian.collateral.application.CollateralService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Valuation history (append-only); a new valuation revalues the collateral and its links' LTV. */
@RestController
public class CollateralValuationsController implements CollateralValuationsApi {
    private final CollateralService service;
    private final Actions actions;

    public CollateralValuationsController(CollateralService service, Actions actions) {
        this.service = service;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<CollateralValuation> collateralValuationsCreate(UUID collateralId, String idempotencyKey,
                                                                         CollateralValuationCreate body) {
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("collateral_id", collateralId.toString());
        fingerprint.put("body", actions.tree(body));
        return actions.create(idempotencyKey, fingerprint, CollateralValuation.class,
                () -> service.addValuation(collateralId, body));
    }
}
