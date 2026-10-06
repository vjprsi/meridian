package io.meridian.treasurydeal.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.treasurydeal.api.generated.TreasurySsisApi;
import io.meridian.treasurydeal.api.generated.model.TreasurySsi;
import io.meridian.treasurydeal.api.generated.model.TreasurySsiCreate;
import io.meridian.treasurydeal.api.generated.model.TreasurySsiUpdate;
import io.meridian.treasurydeal.api.generated.model.TreasurySsisVerifyRequest;
import io.meridian.treasurydeal.application.CounterpartyService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Standing settlement instructions with four-eyes verification (re-verification after payment detail changes). */
@RestController
public class TreasurySsisController implements TreasurySsisApi {
    private final CounterpartyService service;
    private final Actions actions;

    public TreasurySsisController(CounterpartyService service, Actions actions) {
        this.service = service;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<TreasurySsi> treasurySsisCreate(String idempotencyKey, TreasurySsiCreate body) {
        return actions.create(idempotencyKey, body, TreasurySsi.class, () -> service.createSsi(actions.tree(body)));
    }

    @Override
    public ResponseEntity<TreasurySsi> treasurySsisUpdate(UUID id, String ifMatch, TreasurySsiUpdate body) {
        TenantContext.require();
        return actions.withEtag(service.updateSsi(id, Actions.version(ifMatch), actions.tree(body)), TreasurySsi.class);
    }

    @Override
    public ResponseEntity<TreasurySsi> treasurySsisVerify(UUID id, String idempotencyKey, TreasurySsisVerifyRequest body) {
        return actions.action(idempotencyKey, id, "verify", body, TreasurySsi.class, () -> service.verifySsi(id));
    }
}
