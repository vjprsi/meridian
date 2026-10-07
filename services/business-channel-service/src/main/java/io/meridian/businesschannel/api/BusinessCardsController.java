package io.meridian.businesschannel.api;

import io.meridian.businesschannel.api.generated.BusinessCardsApi;
import io.meridian.businesschannel.api.generated.model.BusinessCardsChannelsRequest;
import io.meridian.businesschannel.api.generated.model.BusinessCardsCloseRequest;
import io.meridian.businesschannel.api.generated.model.BusinessCardsCreateRequest;
import io.meridian.businesschannel.api.generated.model.BusinessCardsReplaceLimitsRequest;
import io.meridian.businesschannel.api.generated.model.Card;
import io.meridian.businesschannel.api.generated.model.CardAuthorizationPage;
import io.meridian.businesschannel.api.generated.model.CardControl;
import io.meridian.businesschannel.api.generated.model.CardControlPage;
import io.meridian.businesschannel.api.generated.model.CardPage;
import io.meridian.businesschannel.api.generated.model.CardSpendLimit;
import io.meridian.businesschannel.api.generated.model.CardSpendLimitPage;
import io.meridian.businesschannel.application.CardService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Expense cards for employees. */
@RestController
public class BusinessCardsController implements BusinessCardsApi {
    private final CardService cards;
    private final Http http;

    public BusinessCardsController(CardService cards, Http http) {
        this.cards = cards;
        this.http = http;
    }

    @Override
    public ResponseEntity<CardPage> businessCardsList(Integer limit, String cursor) {
        return http.ok(cards.cards(limit, cursor), CardPage.class);
    }

    @Override
    public ResponseEntity<Card> businessCardsCreate(String idempotencyKey, BusinessCardsCreateRequest body) {
        return http.ok(cards.issue(http.body(body), idempotencyKey), Card.class);
    }

    @Override
    public ResponseEntity<Card> businessCardsGet(UUID cardId) {
        return http.ok(cards.card(cardId), Card.class);
    }

    @Override
    public ResponseEntity<Card> businessCardsFreeze(UUID cardId, String idempotencyKey) {
        return http.ok(cards.freeze(cardId, idempotencyKey), Card.class);
    }

    @Override
    public ResponseEntity<Card> businessCardsUnfreeze(UUID cardId, String idempotencyKey) {
        return http.ok(cards.unfreeze(cardId, idempotencyKey), Card.class);
    }

    @Override
    public ResponseEntity<Card> businessCardsClose(UUID cardId, String idempotencyKey, BusinessCardsCloseRequest body) {
        return http.ok(cards.close(cardId, http.body(body), idempotencyKey), Card.class);
    }

    @Override
    public ResponseEntity<CardControlPage> businessCardsControls(UUID cardId, Integer limit, String cursor) {
        return http.ok(cards.controls(cardId, limit, cursor), CardControlPage.class);
    }

    @Override
    public ResponseEntity<CardControl> businessCardsChannels(UUID cardId, String channel, String idempotencyKey,
                                                             BusinessCardsChannelsRequest body) {
        return http.ok(cards.setChannel(cardId, channel, Boolean.TRUE.equals(body.getBlocked()), idempotencyKey),
                CardControl.class);
    }

    @Override
    public ResponseEntity<CardSpendLimitPage> businessCardsLimits(UUID cardId, Integer limit, String cursor) {
        return http.ok(cards.limits(cardId, limit, cursor), CardSpendLimitPage.class);
    }

    @Override
    public ResponseEntity<CardSpendLimit> businessCardsReplaceLimits(UUID cardId, String idempotencyKey,
                                                                     BusinessCardsReplaceLimitsRequest body) {
        return http.ok(cards.setLimit(cardId, http.body(body), idempotencyKey), CardSpendLimit.class);
    }

    @Override
    public ResponseEntity<CardAuthorizationPage> businessCardsAuthorizations(UUID cardId, Integer limit, String cursor) {
        return http.ok(cards.authorizations(cardId, limit, cursor), CardAuthorizationPage.class);
    }
}
