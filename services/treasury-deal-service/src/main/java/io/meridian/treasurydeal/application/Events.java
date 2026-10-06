package io.meridian.treasurydeal.application;

import io.meridian.events.OutboxWriter;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Publishes the treasury deal events (names exactly as declared in spec/domains/treasury.yaml) via the outbox. */
@Component
public class Events {
    static final Set<String> DECLARED = Set.of(
            "treasury.counterparty.created", "treasury.counterparty.approved", "treasury.counterparty.suspended",
            "treasury.counterparty.reactivated", "treasury.ssi.verified", "treasury.limit.approved",
            "treasury.limit.warning_reached", "treasury.limit.breached",
            "treasury.money_market_deal.captured", "treasury.money_market_deal.verified",
            "treasury.money_market_deal.confirmed", "treasury.money_market_deal.settled",
            "treasury.money_market_deal.called", "treasury.money_market_deal.rolled_over",
            "treasury.money_market_deal.matured", "treasury.money_market_deal.cancelled",
            "treasury.fx_deal.captured", "treasury.fx_deal.verified", "treasury.fx_deal.confirmed",
            "treasury.fx_deal.settled", "treasury.fx_deal.fixed", "treasury.fx_deal.revalued", "treasury.fx_deal.cancelled",
            "treasury.security.priced", "treasury.securities_holding.revalued",
            "treasury.securities_holding.encumbrance_changed", "treasury.securities_trade.captured",
            "treasury.securities_trade.settled", "treasury.securities_trade.cancelled",
            "treasury.confirmation.sent", "treasury.confirmation.matched", "treasury.confirmation.unmatched",
            "treasury.confirmation.disputed", "treasury.settlement.instructed", "treasury.settlement.settled",
            "treasury.settlement.failed", "treasury.eod_run.completed");

    private final OutboxWriter outbox;

    public Events(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    /** Publishes inside the caller's transaction; the aggregate type is the table the payload row comes from. */
    public void publish(String type, String aggregateType, JsonNode row) {
        if (!DECLARED.contains(type)) {
            throw new IllegalArgumentException("event " + type + " is not declared in the treasury domain spec");
        }
        outbox.publish(type, aggregateType, UUID.fromString(row.get("id").asString()), row);
    }
}
