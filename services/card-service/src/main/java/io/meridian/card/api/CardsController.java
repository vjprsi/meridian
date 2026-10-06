package io.meridian.card.api;

import io.meridian.card.api.generated.CardsApi;
import io.meridian.card.api.generated.model.Card;
import io.meridian.card.api.generated.model.CardAuthorizationPage;
import io.meridian.card.api.generated.model.CardControlPage;
import io.meridian.card.api.generated.model.CardCreate;
import io.meridian.card.api.generated.model.CardOrder;
import io.meridian.card.api.generated.model.CardPinEvent;
import io.meridian.card.api.generated.model.CardPinEventPage;
import io.meridian.card.api.generated.model.CardReplacement;
import io.meridian.card.api.generated.model.CardSpendLimitPage;
import io.meridian.card.api.generated.model.CardStatusHistoryPage;
import io.meridian.card.api.generated.model.CardUpdate;
import io.meridian.card.api.generated.model.CardsActivateRequest;
import io.meridian.card.api.generated.model.CardsBlockRequest;
import io.meridian.card.api.generated.model.CardsCloseRequest;
import io.meridian.card.api.generated.model.CardsFreezeRequest;
import io.meridian.card.api.generated.model.CardsOrderPhysicalRequest;
import io.meridian.card.api.generated.model.CardsPinRequest;
import io.meridian.card.api.generated.model.CardsPinUnblockRequest;
import io.meridian.card.api.generated.model.CardsReplaceRequest;
import io.meridian.card.application.CardService;
import io.meridian.card.application.CardViews;
import io.meridian.card.application.ControlService;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Cards: issuing, lifecycle, replacement, renewal, physical orders, PIN, views for authorisation. */
@RestController
public class CardsController implements CardsApi {
    private final CardService cards;
    private final CardViews views;
    private final ControlService controls;
    private final Actions actions;
    private final CrudEngine crud;

    public CardsController(CardService cards, CardViews views, ControlService controls, Actions actions, CrudEngine crud) {
        this.cards = cards;
        this.views = views;
        this.controls = controls;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<Card> cardsCreate(String idempotencyKey, CardCreate body) {
        return actions.create(idempotencyKey, body, Card.class, () -> cards.issue(actions.tree(body), idempotencyKey));
    }

    @Override
    public ResponseEntity<Card> cardsUpdate(UUID id, String ifMatch, CardUpdate body) {
        return actions.guardedUpdate(crud, "card", Card.class, id, ifMatch, body, CardService.CARD_PROTECTED,
                "replace the card or use the lifecycle actions");
    }

    @Override
    public ResponseEntity<Card> cardsActivate(UUID id, String idempotencyKey, CardsActivateRequest body) {
        return actions.action(idempotencyKey, id, "activate", body, Card.class,
                () -> cards.activate(id, Http.text(actions.tree(body), "activation_code")));
    }

    @Override
    public ResponseEntity<Card> cardsFreeze(UUID id, String idempotencyKey, CardsFreezeRequest body) {
        return actions.action(idempotencyKey, id, "freeze", body, Card.class,
                () -> cards.freeze(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<Card> cardsUnfreeze(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "unfreeze", null, Card.class, () -> cards.unfreeze(id));
    }

    @Override
    public ResponseEntity<Card> cardsBlock(UUID id, String idempotencyKey, CardsBlockRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "block", body, Card.class,
                () -> cards.block(id, Http.text(req, "status"), Http.text(req, "reason")));
    }

    @Override
    public ResponseEntity<Card> cardsClose(UUID id, String idempotencyKey, CardsCloseRequest body) {
        return actions.action(idempotencyKey, id, "close", body, Card.class,
                () -> cards.close(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<CardReplacement> cardsReplace(UUID id, String idempotencyKey, CardsReplaceRequest body) {
        return actions.action(idempotencyKey, id, "replace", body, CardReplacement.class,
                () -> cards.replace(id, actions.tree(body), idempotencyKey));
    }

    @Override
    public ResponseEntity<Card> cardsRenew(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "renew", null, Card.class, () -> cards.renew(id, idempotencyKey));
    }

    @Override
    public ResponseEntity<CardOrder> cardsOrderPhysical(UUID id, String idempotencyKey, CardsOrderPhysicalRequest body) {
        return actions.action(idempotencyKey, id, "order-physical", body, CardOrder.class,
                () -> cards.orderPhysical(id, actions.tree(body)));
    }

    @Override
    public ResponseEntity<CardPinEvent> cardsPin(UUID id, String idempotencyKey, CardsPinRequest body) {
        JsonNode req = actions.tree(body);
        // only a SHA-256 of the request is kept for idempotency; the PIN block itself is never stored here
        return actions.action(idempotencyKey, id, "pin", body, CardPinEvent.class,
                () -> cards.setPin(id, Http.text(req, "encrypted_pin_block"), Http.text(req, "key_id"), idempotencyKey));
    }

    @Override
    public ResponseEntity<CardPinEvent> cardsPinUnblock(UUID id, String idempotencyKey, CardsPinUnblockRequest body) {
        return actions.action(idempotencyKey, id, "pin-unblock", body, CardPinEvent.class,
                () -> cards.unblockPin(id, Http.text(actions.tree(body), "reason"), idempotencyKey));
    }

    @Override
    public ResponseEntity<CardStatusHistoryPage> cardsStatusHistory(UUID id, Integer limit, String cursor) {
        crud.get("card", Card.class, Crud.params().with("id", id));
        return crud.list("card_status_history", CardStatusHistoryPage.class,
                Crud.params().with("card_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<CardPinEventPage> cardsPinEvents(UUID id, Integer limit, String cursor) {
        crud.get("card", Card.class, Crud.params().with("id", id));
        return crud.list("card_pin_event", CardPinEventPage.class,
                Crud.params().with("card_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<CardAuthorizationPage> cardsAuthorizations(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        return ResponseEntity.ok(actions.model(views.authorizations(id, limit, cursor), CardAuthorizationPage.class));
    }

    @Override
    public ResponseEntity<CardControlPage> cardsControls(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        return ResponseEntity.ok(actions.model(controls.forCard("card_control", id, limit, cursor), CardControlPage.class));
    }

    @Override
    public ResponseEntity<CardSpendLimitPage> cardsLimits(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        return ResponseEntity.ok(actions.model(views.limits(id, limit, cursor), CardSpendLimitPage.class));
    }

    @Override
    public ResponseEntity<Map<String, Object>> cardsAuthContext(UUID id) {
        TenantContext.require();
        return actions.object(cards.authContext(id));
    }

    @Override
    public ResponseEntity<Map<String, Object>> cardsExpiryScan(String idempotencyKey, LocalDate asOf, Integer windowDays) {
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("as_of", asOf == null ? null : asOf.toString());
        params.put("window_days", windowDays);
        return actions.objectAction(idempotencyKey, "expiry-scan", params, () -> cards.expiryScan(asOf, windowDays));
    }
}
