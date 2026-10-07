package io.meridian.customerchannel.api;

import io.meridian.customerchannel.api.generated.MyCardsApi;
import io.meridian.customerchannel.api.generated.model.Card;
import io.meridian.customerchannel.api.generated.model.CardControl;
import io.meridian.customerchannel.api.generated.model.CardControlPage;
import io.meridian.customerchannel.api.generated.model.CardPage;
import io.meridian.customerchannel.api.generated.model.CardSpendLimit;
import io.meridian.customerchannel.api.generated.model.CardSpendLimitPage;
import io.meridian.customerchannel.api.generated.model.MyCardsChannelsRequest;
import io.meridian.customerchannel.api.generated.model.MyCardsReplaceLimitsRequest;
import io.meridian.customerchannel.api.generated.model.MyCardsReportLostRequest;
import io.meridian.customerchannel.application.CardService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The customer's cards, channel blocks and spend limits. */
@RestController
public class MyCardsController implements MyCardsApi {
    private final CardService cards;
    private final Http http;

    public MyCardsController(CardService cards, Http http) {
        this.cards = cards;
        this.http = http;
    }

    @Override
    public ResponseEntity<CardPage> myCardsList(Integer limit, String cursor) {
        return http.ok(cards.cards(limit, cursor), CardPage.class);
    }

    @Override
    public ResponseEntity<Card> myCardsGet(UUID cardId) {
        return http.ok(cards.card(cardId), Card.class);
    }

    @Override
    public ResponseEntity<Card> myCardsFreeze(UUID cardId, String idempotencyKey) {
        return http.ok(cards.freeze(cardId, idempotencyKey), Card.class);
    }

    @Override
    public ResponseEntity<Card> myCardsUnfreeze(UUID cardId, String idempotencyKey) {
        return http.ok(cards.unfreeze(cardId, idempotencyKey), Card.class);
    }

    @Override
    public ResponseEntity<Card> myCardsReportLost(UUID cardId, String idempotencyKey, MyCardsReportLostRequest body) {
        return http.ok(cards.reportLost(cardId, http.body(body), idempotencyKey), Card.class);
    }

    @Override
    public ResponseEntity<CardControlPage> myCardsControls(UUID cardId, Integer limit, String cursor) {
        return http.ok(cards.controls(cardId, limit, cursor), CardControlPage.class);
    }

    @Override
    public ResponseEntity<CardControl> myCardsChannels(UUID cardId, String channel, String idempotencyKey,
                                                       MyCardsChannelsRequest body) {
        boolean blocked = http.body(body).path("blocked").asBoolean(true);
        return http.ok(cards.setChannel(cardId, channel, blocked, idempotencyKey), CardControl.class);
    }

    @Override
    public ResponseEntity<CardSpendLimitPage> myCardsLimits(UUID cardId, Integer limit, String cursor) {
        return http.ok(cards.limits(cardId, limit, cursor), CardSpendLimitPage.class);
    }

    @Override
    public ResponseEntity<CardSpendLimit> myCardsReplaceLimits(UUID cardId, String idempotencyKey,
                                                               MyCardsReplaceLimitsRequest body) {
        return http.ok(cards.setLimit(cardId, http.body(body), idempotencyKey), CardSpendLimit.class);
    }
}
