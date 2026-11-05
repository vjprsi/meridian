package io.meridian.customerchannel.application;

import static io.meridian.customerchannel.application.AccountService.positive;
import static io.meridian.customerchannel.application.Ownership.me;
import static io.meridian.customerchannel.application.Ownership.mine;
import static io.meridian.customerchannel.application.ProfileService.required;
import static io.meridian.customerchannel.application.ProfileService.text;

import io.meridian.common.error.ApiException;
import io.meridian.customerchannel.application.Downstream.Query;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** The customer's cards: freeze / unfreeze, lost or stolen with replacement, channel blocks and spend limits. */
@Service
public class CardService {
    private static final String CARDS = "card-service";
    private static final Set<String> HIDDEN = Set.of("CLOSED", "REPLACED");
    /** Channels a customer may block; ANY would be a freeze, which has its own action. */
    private static final Set<String> CHANNELS = Set.of("ECOM", "POS", "ATM", "CONTACTLESS", "MOTO", "RECURRING");

    private final Downstream down;
    private final Ownership own;
    private final StepUp stepUp;
    private final ActionAudit audit;

    public CardService(Downstream down, Ownership own, StepUp stepUp, ActionAudit audit) {
        this.down = down;
        this.own = own;
        this.stepUp = stepUp;
        this.audit = audit;
    }

    public JsonNode cards(Integer limit, String cursor) {
        return down.page(down.get(CARDS, "/v1/cards", Query.of("customer_id", me()).paging(limit, cursor)),
                r -> mine(r, "customer_id") && !HIDDEN.contains(r.path("status").asString("")));
    }

    public JsonNode card(UUID id) {
        return own.card(id, "myCardsGet");
    }

    /** Activates a newly issued card the customer owns (step-up). */
    public JsonNode activate(UUID id, String key) {
        String action = "myCardsActivate";
        own.card(id, action);
        stepUp.require(action, "CARD", id);
        JsonNode r = down.post(CARDS, "/v1/cards/" + id + "/activate", null, key, "activate");
        audit.succeeded(action, "CARD", id, null);
        return r;
    }

    public JsonNode freeze(UUID id, String key) {
        own.card(id, "myCardsFreeze");
        JsonNode r = down.post(CARDS, "/v1/cards/" + id + "/freeze",
                down.json().createObjectNode().put("reason", "Frozen by the customer"), key, "freeze");
        audit.succeeded("myCardsFreeze", "CARD", id, null);
        return r;
    }

    public JsonNode unfreeze(UUID id, String key) {
        own.card(id, "myCardsUnfreeze");
        stepUp.require("myCardsUnfreeze", "CARD", id);
        JsonNode r = down.post(CARDS, "/v1/cards/" + id + "/unfreeze", null, key, "unfreeze");
        audit.succeeded("myCardsUnfreeze", "CARD", id, null);
        return r;
    }

    /** Blocks the card as LOST / STOLEN (never needs step-up: it only protects) and optionally orders a replacement. */
    public JsonNode reportLost(UUID id, JsonNode req, String key) {
        own.card(id, "myCardsReportLost");
        String reason = required(req, "reason");
        if (!reason.equals("LOST") && !reason.equals("STOLEN")) {
            throw ApiException.unprocessable("invalid_reason", "reason must be LOST or STOLEN");
        }
        JsonNode blocked = down.post(CARDS, "/v1/cards/" + id + "/block", down.json().createObjectNode()
                .put("status", reason).put("reason", "Reported " + reason.toLowerCase() + " by the customer"), key, "block");
        JsonNode replacement = null;
        if (req.path("replace").asBoolean(false)) {
            replacement = down.post(CARDS, "/v1/cards/" + id + "/replace",
                    down.json().createObjectNode().put("reason", reason), key, "replace");
        }
        audit.succeeded("myCardsReportLost", "CARD", id,
                replacement == null ? null : replacement.path("new_card_id").asString(null));
        return blocked;
    }

    public JsonNode controls(UUID id, Integer limit, String cursor) {
        own.card(id, "myCardsControls");
        return down.page(down.get(CARDS, "/v1/cards/" + id + "/controls", Query.none().paging(limit, cursor)),
                r -> id.toString().equals(r.path("card_id").asString(null)));
    }

    /** Creates or updates the card's CHANNEL_DENY control for one channel. */
    public JsonNode setChannel(UUID id, String channel, boolean blocked, String key) {
        own.card(id, "myCardsChannels");
        if (!CHANNELS.contains(channel)) {
            throw ApiException.unprocessable("invalid_channel", "channel must be one of " + CHANNELS);
        }
        JsonNode existing = null;
        for (JsonNode c : down.all(CARDS, "/v1/cards/" + id + "/controls", Query.none(), 500)) {
            if (id.toString().equals(c.path("card_id").asString(null))
                    && "CHANNEL_DENY".equals(c.path("control_type").asString(null))
                    && channel.equals(c.path("channel").asString(null))) {
                existing = c;
                break;
            }
        }
        JsonNode result = existing != null
                ? down.patch(CARDS, "/v1/card-controls/" + existing.path("id").asString(),
                        down.json().createObjectNode().put("is_active", blocked), existing.path("row_version").asInt())
                : down.post(CARDS, "/v1/card-controls", down.json().createObjectNode()
                        .put("card_id", id.toString()).put("control_type", "CHANNEL_DENY").put("channel", channel)
                        .put("is_active", blocked), key, "control-" + channel);
        audit.succeeded("myCardsChannels", "CARD", id, result.path("id").asString(null));
        return result;
    }

    public JsonNode limits(UUID id, Integer limit, String cursor) {
        own.card(id, "myCardsLimits");
        return down.page(down.get(CARDS, "/v1/cards/" + id + "/limits", Query.none().paging(limit, cursor)),
                r -> id.toString().equals(r.path("card_id").asString(null)));
    }

    /** Creates or updates the card's limit for one period and channel (card-service enforces program maximums). */
    public JsonNode setLimit(UUID id, JsonNode req, String key) {
        String action = "myCardsReplaceLimits";
        own.card(id, action);
        String period = required(req, "period");
        String channel = text(req, "channel") == null ? "ANY" : text(req, "channel");
        String amount = positive(req, "max_amount").toPlainString();
        stepUp.require(action, "CARD", id);
        JsonNode existing = null;
        for (JsonNode l : down.all(CARDS, "/v1/cards/" + id + "/limits", Query.none(), 500)) {
            if (id.toString().equals(l.path("card_id").asString(null)) && period.equals(l.path("period").asString(null))
                    && channel.equals(l.path("channel").asString(null))) {
                existing = l;
                break;
            }
        }
        JsonNode result;
        if (existing != null) {
            ObjectNode patch = down.json().createObjectNode().put("max_amount", amount).put("is_active", true);
            result = down.patch(CARDS, "/v1/card-spend-limits/" + existing.path("id").asString(), patch,
                    existing.path("row_version").asInt());
        } else {
            String currency = text(req, "currency");
            if (currency == null) {
                throw ApiException.unprocessable("missing_field", "currency is required for a new limit");
            }
            result = down.post(CARDS, "/v1/card-spend-limits", down.json().createObjectNode()
                    .put("card_id", id.toString()).put("period", period).put("channel", channel)
                    .put("max_amount", amount).put("currency", currency.toUpperCase()).put("is_active", true),
                    key, "limit");
        }
        audit.succeeded(action, "CARD", id, result.path("id").asString(null));
        return result;
    }
}
