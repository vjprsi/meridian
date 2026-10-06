package io.meridian.treasurydeal.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.treasurydeal.api.generated.TreasuryCounterpartyLimitsApi;
import io.meridian.treasurydeal.api.generated.model.TreasuryCounterpartyLimit;
import io.meridian.treasurydeal.api.generated.model.TreasuryCounterpartyLimitCreate;
import io.meridian.treasurydeal.api.generated.model.TreasuryCounterpartyLimitUpdate;
import io.meridian.treasurydeal.api.generated.model.TreasuryCounterpartyLimitsApproveRequest;
import io.meridian.treasurydeal.api.generated.model.TreasuryCounterpartyLimitsCheckRequest;
import io.meridian.treasurydeal.api.generated.model.TreasuryLimitUtilisationPage;
import io.meridian.treasurydeal.application.LimitService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Counterparty limits: maker-checker, pre-deal checks and utilisation history. */
@RestController
public class TreasuryCounterpartyLimitsController implements TreasuryCounterpartyLimitsApi {
    private final LimitService service;
    private final Actions actions;
    private final CrudEngine crud;

    public TreasuryCounterpartyLimitsController(LimitService service, Actions actions, CrudEngine crud) {
        this.service = service;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<TreasuryCounterpartyLimit> treasuryCounterpartyLimitsCreate(String idempotencyKey,
                                                                                     TreasuryCounterpartyLimitCreate body) {
        return actions.create(idempotencyKey, body, TreasuryCounterpartyLimit.class, () -> service.create(actions.tree(body)));
    }

    @Override
    public ResponseEntity<TreasuryCounterpartyLimit> treasuryCounterpartyLimitsUpdate(UUID id, String ifMatch,
                                                                                     TreasuryCounterpartyLimitUpdate body) {
        TenantContext.require();
        return actions.withEtag(service.update(id, Actions.version(ifMatch), actions.tree(body)), TreasuryCounterpartyLimit.class);
    }

    @Override
    public ResponseEntity<TreasuryCounterpartyLimit> treasuryCounterpartyLimitsApprove(UUID id, String idempotencyKey,
                                                                                      TreasuryCounterpartyLimitsApproveRequest body) {
        return actions.action(idempotencyKey, id, "approve", body, TreasuryCounterpartyLimit.class, () -> service.approve(id));
    }

    @Override
    public ResponseEntity<Map<String, Object>> treasuryCounterpartyLimitsCheck(String idempotencyKey,
                                                                              TreasuryCounterpartyLimitsCheckRequest body) {
        JsonNode req = actions.tree(body);
        return actions.objectAction(idempotencyKey, "limit-check", body, () -> service.check(
                Http.uuid(req, "treasury_counterparty_id"), Http.text(req, "limit_type"), Http.decimal(req, "amount"),
                Http.text(req, "currency"), req.hasNonNull("tenor_days") ? req.get("tenor_days").asInt() : null));
    }

    @Override
    public ResponseEntity<TreasuryLimitUtilisationPage> treasuryCounterpartyLimitsUtilisations(UUID id, Integer limit,
                                                                                              String cursor) {
        TenantContext.require();
        crud.get("treasury_counterparty_limit", TreasuryCounterpartyLimit.class, Crud.params().with("id", id));
        return crud.list("treasury_limit_utilisation", TreasuryLimitUtilisationPage.class, Crud.params()
                .with("treasury_counterparty_limit_id", id).with("limit", limit).with("cursor", cursor));
    }
}
