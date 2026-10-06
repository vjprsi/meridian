package io.meridian.cardauthorization.application;

import io.meridian.cardauthorization.domain.AuthContext;
import io.meridian.cardauthorization.domain.Periods;
import io.meridian.cardauthorization.infrastructure.AuthStore;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Spend-limit / velocity counter maintenance (always inside the caller's transaction). */
@Component
public class Counters {
    private final AuthStore store;

    public Counters(AuthStore store) {
        this.store = store;
    }

    /** Adds an approved amount (and {@code count} transactions) to every counted limit's current period. */
    public void add(UUID cardId, List<AuthContext.Limit> limits, OffsetDateTime now, String currency, BigDecimal amount, int count) {
        for (AuthContext.Limit l : limits) {
            store.addToCounter(cardId, l.id(), l.period(), l.channel(), Periods.start(l.period(), now), currency, amount, count);
        }
    }

    /**
     * Gives back usage of an authorisation: every counter of the card for the period the authorisation fell in,
     * matching its channel (or ANY) and billing currency.
     */
    public void giveBack(JsonNode auth, BigDecimal amount, int count) {
        if (amount.signum() <= 0 && count <= 0) {
            return;
        }
        UUID cardId = UUID.fromString(auth.get("card_id").asString());
        OffsetDateTime at = OffsetDateTime.parse(auth.get("created_at").asString());
        String channel = auth.get("channel").asString();
        String currency = auth.get("billing_currency").asString();
        for (JsonNode c : store.counters(cardId)) {
            String period = c.get("period").asString();
            OffsetDateTime start = OffsetDateTime.parse(c.get("period_start").asString());
            String cch = c.get("channel").asString();
            if (start.toInstant().equals(Periods.start(period, at).toInstant())
                    && ("ANY".equals(cch) || cch.equals(channel)) && currency.equals(c.get("currency").asString())) {
                store.subtractFromCounter(UUID.fromString(c.get("id").asString()), amount, count);
            }
        }
    }
}
