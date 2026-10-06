package io.meridian.treasurydeal.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.treasurydeal.api.generated.TreasuryCounterpartiesApi;
import io.meridian.treasurydeal.api.generated.model.TreasuryCounterpartiesApproveRequest;
import io.meridian.treasurydeal.api.generated.model.TreasuryCounterpartiesReactivateRequest;
import io.meridian.treasurydeal.api.generated.model.TreasuryCounterpartiesSuspendRequest;
import io.meridian.treasurydeal.api.generated.model.TreasuryCounterparty;
import io.meridian.treasurydeal.api.generated.model.TreasuryCounterpartyCreate;
import io.meridian.treasurydeal.api.generated.model.TreasuryCounterpartyUpdate;
import io.meridian.treasurydeal.application.CounterpartyService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Treasury counterparties: maker-checker onboarding, suspension and aggregated exposure. */
@RestController
public class TreasuryCounterpartiesController implements TreasuryCounterpartiesApi {
    private final CounterpartyService service;
    private final Actions actions;
    private final CrudEngine crud;

    public TreasuryCounterpartiesController(CounterpartyService service, Actions actions, CrudEngine crud) {
        this.service = service;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<TreasuryCounterparty> treasuryCounterpartiesCreate(String idempotencyKey, TreasuryCounterpartyCreate body) {
        return actions.create(idempotencyKey, body, TreasuryCounterparty.class, () -> service.create(actions.tree(body)));
    }

    @Override
    public ResponseEntity<TreasuryCounterparty> treasuryCounterpartiesUpdate(UUID id, String ifMatch, TreasuryCounterpartyUpdate body) {
        TenantContext.require();
        CounterpartyService.validate(actions.tree(body));
        ResponseEntity<TreasuryCounterparty> out = crud.update("treasury_counterparty", TreasuryCounterparty.class, body,
                ifMatch, Crud.params().with("id", id));
        service.resubmitIfPending(id); // changed content of a pending counterparty needs a fresh approval request
        return out;
    }

    @Override
    public ResponseEntity<TreasuryCounterparty> treasuryCounterpartiesApprove(UUID id, String idempotencyKey,
                                                                             TreasuryCounterpartiesApproveRequest body) {
        return actions.action(idempotencyKey, id, "approve", body, TreasuryCounterparty.class, () -> service.approve(id));
    }

    @Override
    public ResponseEntity<TreasuryCounterparty> treasuryCounterpartiesSuspend(UUID id, String idempotencyKey,
                                                                             TreasuryCounterpartiesSuspendRequest body) {
        return actions.action(idempotencyKey, id, "suspend", body, TreasuryCounterparty.class,
                () -> service.suspend(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<TreasuryCounterparty> treasuryCounterpartiesReactivate(UUID id, String idempotencyKey,
                                                                                TreasuryCounterpartiesReactivateRequest body) {
        return actions.action(idempotencyKey, id, "reactivate", body, TreasuryCounterparty.class,
                () -> service.reactivate(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<Map<String, Object>> treasuryCounterpartiesExposure(UUID id) {
        TenantContext.require();
        return actions.object(service.exposure(id));
    }
}
