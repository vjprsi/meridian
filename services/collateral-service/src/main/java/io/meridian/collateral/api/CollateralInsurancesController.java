package io.meridian.collateral.api;

import io.meridian.collateral.api.generated.CollateralInsurancesApi;
import io.meridian.collateral.api.generated.model.CollateralInsurance;
import io.meridian.collateral.api.generated.model.CollateralInsuranceCreate;
import io.meridian.collateral.api.generated.model.CollateralInsuranceUpdate;
import io.meridian.collateral.application.CollateralService;
import io.meridian.common.tenancy.TenantContext;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Insurance policies covering collateral (expiry monitored by the end-of-day job). */
@RestController
public class CollateralInsurancesController implements CollateralInsurancesApi {
    private final CollateralService service;
    private final Actions actions;

    public CollateralInsurancesController(CollateralService service, Actions actions) {
        this.service = service;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<CollateralInsurance> collateralInsurancesCreate(String idempotencyKey,
                                                                         CollateralInsuranceCreate body) {
        return actions.create(idempotencyKey, body, CollateralInsurance.class, () -> service.addInsurance(body));
    }

    @Override
    public ResponseEntity<CollateralInsurance> collateralInsurancesUpdate(UUID id, String ifMatch,
                                                                         CollateralInsuranceUpdate body) {
        TenantContext.require();
        return ResponseEntity.ok(actions.model(service.updateInsurance(id, ifMatch, body), CollateralInsurance.class));
    }
}
