package io.meridian.treasurydeal.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.treasurydeal.api.generated.MoneyMarketDealsApi;
import io.meridian.treasurydeal.api.generated.model.MoneyMarketDeal;
import io.meridian.treasurydeal.api.generated.model.MoneyMarketDealCreate;
import io.meridian.treasurydeal.api.generated.model.MoneyMarketDealUpdate;
import io.meridian.treasurydeal.api.generated.model.MoneyMarketDealsAccrueRequest;
import io.meridian.treasurydeal.api.generated.model.MoneyMarketDealsCallRequest;
import io.meridian.treasurydeal.api.generated.model.MoneyMarketDealsCancelRequest;
import io.meridian.treasurydeal.api.generated.model.MoneyMarketDealsConfirmRequest;
import io.meridian.treasurydeal.api.generated.model.MoneyMarketDealsRolloverRequest;
import io.meridian.treasurydeal.application.MoneyMarketService;
import io.meridian.treasurydeal.infrastructure.Rows;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Money market deals: capture, four-eyes verification, confirmation, settlement, accrual, call, maturity, rollover. */
@RestController
public class MoneyMarketDealsController implements MoneyMarketDealsApi {
    private final MoneyMarketService service;
    private final Actions actions;

    public MoneyMarketDealsController(MoneyMarketService service, Actions actions) {
        this.service = service;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<MoneyMarketDeal> moneyMarketDealsCreate(String idempotencyKey, MoneyMarketDealCreate body) {
        return actions.create(idempotencyKey, body, MoneyMarketDeal.class, () -> service.capture(actions.tree(body)));
    }

    @Override
    public ResponseEntity<MoneyMarketDeal> moneyMarketDealsUpdate(UUID id, String ifMatch, MoneyMarketDealUpdate body) {
        TenantContext.require();
        return actions.withEtag(service.amend(id, Actions.version(ifMatch), actions.tree(body)), MoneyMarketDeal.class);
    }

    @Override
    public ResponseEntity<MoneyMarketDeal> moneyMarketDealsVerify(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "verify", null, MoneyMarketDeal.class, () -> service.verify(id));
    }

    @Override
    public ResponseEntity<MoneyMarketDeal> moneyMarketDealsConfirm(UUID id, String idempotencyKey, MoneyMarketDealsConfirmRequest body) {
        return actions.action(idempotencyKey, id, "confirm", body, MoneyMarketDeal.class,
                () -> service.confirm(id, Http.text(actions.tree(body), "manual_reference")));
    }

    @Override
    public ResponseEntity<MoneyMarketDeal> moneyMarketDealsSettle(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "settle", null, MoneyMarketDeal.class, () -> service.settle(id));
    }

    @Override
    public ResponseEntity<MoneyMarketDeal> moneyMarketDealsRollover(UUID id, String idempotencyKey, MoneyMarketDealsRolloverRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "rollover", body, MoneyMarketDeal.class, () -> service.rollover(id,
                Http.decimal(req, "new_rate"), Rows.date(req, "new_maturity_date"), Http.text(req, "rollover_type"), null));
    }

    @Override
    public ResponseEntity<MoneyMarketDeal> moneyMarketDealsMature(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "mature", null, MoneyMarketDeal.class, () -> service.mature(id, null));
    }

    @Override
    public ResponseEntity<MoneyMarketDeal> moneyMarketDealsCall(UUID id, String idempotencyKey, MoneyMarketDealsCallRequest body) {
        return actions.action(idempotencyKey, id, "call", body, MoneyMarketDeal.class,
                () -> service.call(id, Rows.date(actions.tree(body), "call_date")));
    }

    @Override
    public ResponseEntity<MoneyMarketDeal> moneyMarketDealsCancel(UUID id, String idempotencyKey, MoneyMarketDealsCancelRequest body) {
        return actions.action(idempotencyKey, id, "cancel", body, MoneyMarketDeal.class,
                () -> service.cancel(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<MoneyMarketDeal> moneyMarketDealsAccrue(UUID id, String idempotencyKey, MoneyMarketDealsAccrueRequest body) {
        return actions.action(idempotencyKey, id, "accrue", body, MoneyMarketDeal.class,
                () -> service.accrue(id, Rows.date(actions.tree(body), "as_of")));
    }

    @Override
    public ResponseEntity<String> moneyMarketDealsConfirmationMessage(UUID id) {
        TenantContext.require();
        return Actions.text(service.confirmationMessage(id), "mt320-" + id + ".txt");
    }
}
