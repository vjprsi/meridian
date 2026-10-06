package io.meridian.treasurydeal.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.treasurydeal.api.generated.TreasuryFxDealsApi;
import io.meridian.treasurydeal.api.generated.model.TreasuryFxDeal;
import io.meridian.treasurydeal.api.generated.model.TreasuryFxDealCreate;
import io.meridian.treasurydeal.api.generated.model.TreasuryFxDealLegPage;
import io.meridian.treasurydeal.api.generated.model.TreasuryFxDealUpdate;
import io.meridian.treasurydeal.api.generated.model.TreasuryFxDealsCancelRequest;
import io.meridian.treasurydeal.api.generated.model.TreasuryFxDealsConfirmRequest;
import io.meridian.treasurydeal.api.generated.model.TreasuryFxDealsFixRequest;
import io.meridian.treasurydeal.api.generated.model.TreasuryFxDealsRevalueRequest;
import io.meridian.treasurydeal.api.generated.model.TreasuryFxDealsSettleRequest;
import io.meridian.treasurydeal.application.FxDealService;
import io.meridian.treasurydeal.infrastructure.Rows;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Treasury FX deals with legs: capture, verification, confirmation, leg settlement, NDF fixing, MTM, cancellation. */
@RestController
public class TreasuryFxDealsController implements TreasuryFxDealsApi {
    private final FxDealService service;
    private final Actions actions;
    private final CrudEngine crud;

    public TreasuryFxDealsController(FxDealService service, Actions actions, CrudEngine crud) {
        this.service = service;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<TreasuryFxDeal> treasuryFxDealsCreate(String idempotencyKey, TreasuryFxDealCreate body) {
        return actions.create(idempotencyKey, body, TreasuryFxDeal.class, () -> service.capture(actions.tree(body)));
    }

    @Override
    public ResponseEntity<TreasuryFxDeal> treasuryFxDealsGet(UUID id) {
        TenantContext.require();
        return actions.withEtag(service.get(id), TreasuryFxDeal.class);
    }

    @Override
    public ResponseEntity<TreasuryFxDeal> treasuryFxDealsUpdate(UUID id, String ifMatch, TreasuryFxDealUpdate body) {
        TenantContext.require();
        return actions.withEtag(service.amend(id, Actions.version(ifMatch), actions.tree(body)), TreasuryFxDeal.class);
    }

    @Override
    public ResponseEntity<TreasuryFxDealLegPage> treasuryFxDealsLegs(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        service.get(id);
        return crud.list("treasury_fx_deal_leg", TreasuryFxDealLegPage.class,
                Crud.params().with("treasury_fx_deal_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<TreasuryFxDeal> treasuryFxDealsVerify(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "verify", null, TreasuryFxDeal.class, () -> service.verify(id));
    }

    @Override
    public ResponseEntity<TreasuryFxDeal> treasuryFxDealsConfirm(UUID id, String idempotencyKey, TreasuryFxDealsConfirmRequest body) {
        return actions.action(idempotencyKey, id, "confirm", body, TreasuryFxDeal.class,
                () -> service.confirm(id, Http.text(actions.tree(body), "manual_reference")));
    }

    @Override
    public ResponseEntity<TreasuryFxDeal> treasuryFxDealsSettle(UUID id, String idempotencyKey, TreasuryFxDealsSettleRequest body) {
        return actions.action(idempotencyKey, id, "settle", body, TreasuryFxDeal.class,
                () -> service.settle(id, Http.text(actions.tree(body), "leg_type")));
    }

    @Override
    public ResponseEntity<TreasuryFxDeal> treasuryFxDealsFix(UUID id, String idempotencyKey, TreasuryFxDealsFixRequest body) {
        return actions.action(idempotencyKey, id, "fix", body, TreasuryFxDeal.class,
                () -> service.fix(id, Http.decimal(actions.tree(body), "fixing_rate")));
    }

    @Override
    public ResponseEntity<TreasuryFxDeal> treasuryFxDealsRevalue(UUID id, String idempotencyKey, TreasuryFxDealsRevalueRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "revalue", body, TreasuryFxDeal.class,
                () -> service.revalue(id, Rows.date(req, "valuation_date"), Http.decimal(req, "market_rate")));
    }

    @Override
    public ResponseEntity<TreasuryFxDeal> treasuryFxDealsCancel(UUID id, String idempotencyKey, TreasuryFxDealsCancelRequest body) {
        return actions.action(idempotencyKey, id, "cancel", body, TreasuryFxDeal.class,
                () -> service.cancel(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<String> treasuryFxDealsConfirmationMessage(UUID id) {
        TenantContext.require();
        return Actions.text(service.confirmationMessage(id), "mt300-" + id + ".txt");
    }
}
