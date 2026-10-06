package io.meridian.treasurydeal.application;

import io.meridian.common.error.ApiException;
import io.meridian.treasurydeal.domain.Confirmations;
import io.meridian.treasurydeal.domain.Lifecycle;
import io.meridian.treasurydeal.infrastructure.Rows;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Deal confirmations: outbound MT320 / MT300 generation and sending, inbound capture (fields or raw MT text), matching
 * against the deal (a match confirms a VERIFIED deal), and disputes.
 */
@Service
public class ConfirmationService {
    static final String TABLE = "treasury_deal_confirmation";
    static final Set<String> CONFIRMABLE = Set.of("VERIFIED", "CONFIRMED", "SETTLED");

    private final Rows rows;
    private final Events events;
    private final MoneyMarketService moneyMarket;
    private final FxDealService fx;

    public ConfirmationService(Rows rows, Events events, MoneyMarketService moneyMarket, FxDealService fx) {
        this.rows = rows;
        this.events = events;
        this.moneyMarket = moneyMarket;
        this.fx = fx;
    }

    /** Records an inbound confirmation; with a deal_id it is matched immediately. */
    @Transactional
    public ObjectNode receive(JsonNode req) {
        ObjectNode v = rows.columns(TABLE, req);
        String direction = Rows.str(v, "direction");
        if (!"INBOUND".equals(direction)) {
            throw ApiException.unprocessable("outbound_via_generate", "Outbound confirmations are created with POST /generate");
        }
        String dealType = Rows.str(v, "deal_type");
        Support.required(dealType, "deal_type");
        if ("SECURITY".equals(dealType)) {
            throw ApiException.unprocessable("unsupported_deal_type", "Securities confirmations (MT541/543) are matched by the custodian");
        }
        if (!v.hasNonNull("message_type")) {
            v.put("message_type", "FX".equals(dealType) ? "MT300" : "MT320");
        }
        JsonNode fields = v.get("fields");
        if ((fields == null || fields.isNull() || fields.isEmpty()) && Rows.str(v, "message_text") == null) {
            throw ApiException.unprocessable("confirmation_content_required", "Send the confirmation fields or the message_text");
        }
        if (fields == null || fields.isNull() || fields.isEmpty()) {
            try {
                ObjectNode parsed = rows.object();
                Confirmations.parse(Rows.str(v, "message_type"), Rows.str(v, "message_text")).forEach(parsed::put);
                v.set("fields", parsed);
            } catch (RuntimeException e) {
                throw ApiException.unprocessable("unparseable_message", "message_text could not be parsed: " + e.getMessage());
            }
        }
        v.put("status", "RECEIVED");
        UUID dealId = Rows.uuid(v, "deal_id");
        v.remove("deal_id");
        ObjectNode conf = rows.insert(TABLE, v);
        return dealId == null ? conf : match(Rows.uuid(conf, "id"), dealId);
    }

    /** Generates our confirmation for a verified deal and marks it SENT. */
    @Transactional
    public ObjectNode generate(String dealType, UUID dealId, String channel) {
        Support.required(dealType, "deal_type");
        Support.required(dealId, "deal_id");
        String ch = channel == null ? "SWIFT" : channel;
        JsonNode deal = deal(dealType, dealId);
        if (!CONFIRMABLE.contains(Rows.str(deal, "status"))) {
            throw ApiException.conflict("invalid_transition", "Confirmations are sent for verified deals (deal is "
                    + Rows.str(deal, "status") + ")");
        }
        String text;
        String type;
        if ("FX".equals(dealType)) {
            Confirmations.Fx c = fx.confirmation(deal);
            requireBic(ch, c.theirBic());
            text = Confirmations.mt300(c);
            type = "MT300";
        } else {
            Confirmations.MoneyMarket c = moneyMarket.confirmation(deal);
            requireBic(ch, c.theirBic());
            text = Confirmations.mt320(c);
            type = "MT320";
        }
        ObjectNode v = rows.object();
        v.put("deal_type", dealType);
        v.put("deal_id", dealId.toString());
        v.put("channel", ch);
        v.put("message_type", type);
        v.put("our_reference", Rows.str(deal, "deal_ref"));
        v.put("direction", "OUTBOUND");
        v.put("status", "SENT");
        v.put("message_text", text);
        v.put("sent_at", rows.now());
        ObjectNode out = rows.insert(TABLE, v);
        events.publish("treasury.confirmation.sent", TABLE, out);
        return out;
    }

    private static void requireBic(String channel, String bic) {
        if ("SWIFT".equals(channel) && bic == null) {
            throw ApiException.unprocessable("bic_required", "The counterparty needs a BIC for SWIFT confirmations");
        }
    }

    /** Matches an inbound confirmation against a deal; MATCHED confirms a VERIFIED deal. */
    @Transactional
    public ObjectNode match(UUID id, UUID dealId) {
        Support.required(dealId, "deal_id");
        ObjectNode conf = rows.lock(TABLE, id);
        if (!"INBOUND".equals(Rows.str(conf, "direction"))) {
            throw ApiException.unprocessable("not_inbound", "Only inbound confirmations are matched");
        }
        String status = Rows.str(conf, "status");
        if (!Set.of("RECEIVED", "UNMATCHED", "DISPUTED").contains(status)) {
            throw ApiException.conflict("invalid_transition", "A " + status + " confirmation cannot be matched");
        }
        String dealType = Rows.str(conf, "deal_type");
        JsonNode deal = deal(dealType, dealId);
        if ("CANCELLED".equals(Rows.str(deal, "status"))) {
            throw ApiException.conflict("deal_cancelled", "The deal is cancelled");
        }
        Map<String, String> expected = "FX".equals(dealType) ? Confirmations.expected(fx.confirmation(deal))
                : Confirmations.expected(moneyMarket.confirmation(deal));
        Map<String, String> theirs = new java.util.LinkedHashMap<>();
        JsonNode f = conf.get("fields");
        if (f != null && f.isObject()) {
            f.propertyNames().forEach(n -> theirs.put(n, f.get(n).isNull() ? null : f.get(n).asString()));
        }
        List<Confirmations.Mismatch> mismatches = Confirmations.match(expected, Confirmations.toOurPerspective(theirs));
        ObjectNode v = rows.object();
        v.put("deal_id", dealId.toString());
        ArrayNode mm = v.putArray("mismatch_fields");
        mismatches.forEach(m -> {
            ObjectNode n = mm.addObject();
            n.put("field", m.field());
            n.put("ours", m.ours());
            n.put("theirs", m.theirs());
        });
        if (mismatches.isEmpty()) {
            v.put("status", "MATCHED");
            v.put("matched_at", rows.now());
        } else {
            v.put("status", "UNMATCHED");
        }
        ObjectNode out = rows.update(TABLE, id, v);
        if (mismatches.isEmpty()) {
            events.publish("treasury.confirmation.matched", TABLE, out);
            if ("VERIFIED".equals(Rows.str(deal, "status"))) {
                rows.lock("FX".equals(dealType) ? FxDealService.TABLE : MoneyMarketService.TABLE, dealId);
                if ("FX".equals(dealType)) {
                    fx.markConfirmed(dealId);
                } else {
                    moneyMarket.markConfirmed(dealId);
                }
            }
        } else {
            events.publish("treasury.confirmation.unmatched", TABLE, out);
        }
        return out;
    }

    @Transactional
    public ObjectNode dispute(UUID id, String reason) {
        Support.required(reason, "reason");
        ObjectNode conf = rows.lock(TABLE, id);
        Support.transition(Lifecycle.CONFIRMATION, conf, "DISPUTED", "confirmation");
        ObjectNode v = rows.object();
        v.put("status", "DISPUTED");
        v.put("dispute_reason", reason);
        ObjectNode out = rows.update(TABLE, id, v);
        events.publish("treasury.confirmation.disputed", TABLE, out);
        return out;
    }

    @Transactional(readOnly = true)
    public String message(UUID id) {
        ObjectNode conf = rows.get(TABLE, id);
        String text = Rows.str(conf, "message_text");
        if (text != null) {
            return text;
        }
        if (Rows.uuid(conf, "deal_id") != null && "OUTBOUND".equals(Rows.str(conf, "direction"))) {
            JsonNode deal = deal(Rows.str(conf, "deal_type"), Rows.uuid(conf, "deal_id"));
            return "FX".equals(Rows.str(conf, "deal_type")) ? Confirmations.mt300(fx.confirmation(deal))
                    : Confirmations.mt320(moneyMarket.confirmation(deal));
        }
        StringBuilder b = new StringBuilder();
        JsonNode f = conf.get("fields");
        if (f != null && f.isObject()) {
            f.propertyNames().forEach(n -> b.append(n).append(": ").append(f.get(n).asString()).append('\n'));
        }
        return b.toString();
    }

    private JsonNode deal(String dealType, UUID dealId) {
        String table = switch (dealType) {
            case "FX" -> FxDealService.TABLE;
            case "MONEY_MARKET" -> MoneyMarketService.TABLE;
            default -> throw ApiException.unprocessable("unsupported_deal_type", "Confirmations cover MONEY_MARKET and FX deals");
        };
        return rows.find(table, dealId).orElseThrow(() -> ApiException.unprocessable("unknown_deal",
                dealType + " deal " + dealId + " does not exist"));
    }
}
