package io.meridian.rewards.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.rewards.application.EarnService;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Earns from card and wallet events (binary CloudEvents: ce_type, ce_id, ce_tenantid headers; API payload).
 * Approved card authorisations are ingested as CARD_SPEND, completed wallet payments as WALLET_PAYMENT, and
 * reversed authorisations reverse their awards. Idempotent by source: a redelivered event finds the existing
 * reward transaction. Events without a customer are ignored.
 */
@Component
@ConditionalOnProperty(name = "meridian.rewards.earn-consumer.enabled", havingValue = "true")
public class CardEventsConsumer {
    private static final Logger log = LoggerFactory.getLogger(CardEventsConsumer.class);

    /** CardChannel -> RewardChannel. */
    static final Map<String, String> CHANNELS = Map.of("POS", "POS", "ECOM", "ECOM", "CONTACTLESS", "CONTACTLESS",
            "ATM", "ATM", "MOTO", "ECOM", "RECURRING", "ECOM");

    private final EarnService earn;
    private final JsonMapper json;

    public CardEventsConsumer(EarnService earn, JsonMapper json) {
        this.earn = earn;
        this.json = json;
    }

    @KafkaListener(topics = "#{'${meridian.rewards.earn-consumer.topics:meridian.cards.v1,meridian.wallets.v1}'.split(',')}",
            groupId = "rewards-service-earn")
    public void onEvent(ConsumerRecord<String, String> record) throws Exception {
        String type = header(record, "ce_type");
        String tenant = header(record, "ce_tenantid");
        if (type == null || tenant == null || record.value() == null) {
            return;
        }
        JsonNode p = json.readTree(record.value());
        ObjectNode body = toIngest(type, p);
        try {
            if (body != null) {
                TenantContext.callAs(UUID.fromString(tenant), "rewards-service", () -> earn.ingest(body));
            } else if ("cards.card_authorization.reversed".equals(type) && (p.hasNonNull("parent_authorization_id") || p.hasNonNull("id"))) {
                UUID parent = UUID.fromString(p.hasNonNull("parent_authorization_id")
                        ? p.get("parent_authorization_id").asString() : p.get("id").asString());
                TenantContext.callAs(UUID.fromString(tenant), "rewards-service",
                        () -> earn.reverseSource("CARD_AUTHORIZATION", parent, "Card authorisation reversed"));
            }
        } catch (ApiException e) {
            log.warn("Rewards could not process {} {}: {} {}", type, header(record, "ce_id"), e.code(), e.getMessage());
        }
    }

    /** Maps an event to an ingest body; null when the event does not earn. */
    ObjectNode toIngest(String type, JsonNode p) {
        if (!p.hasNonNull("customer_id") || !p.hasNonNull("id")) {
            return null;
        }
        ObjectNode b = json.createObjectNode();
        b.put("customer_id", p.get("customer_id").asString());
        b.put("source_id", p.get("id").asString());
        if ("cards.card_authorization.approved".equals(type)) {
            b.put("trigger", "CARD_SPEND");
            b.put("source_type", "CARD_AUTHORIZATION");
            String amount = p.hasNonNull("billing_amount") ? p.get("billing_amount").asString() : p.path("transaction_amount").asString(null);
            String currency = p.hasNonNull("billing_currency") ? p.get("billing_currency").asString() : p.path("transaction_currency").asString(null);
            if (amount == null || currency == null) {
                return null;
            }
            b.put("amount", amount);
            b.put("currency", currency);
            copy(p, b, "mcc", "mcc");
            copy(p, b, "merchant_id", "merchant_id");
            copy(p, b, "merchant_country", "merchant_country");
            String channel = CHANNELS.get(p.path("channel").asString(""));
            if (channel != null) {
                b.put("channel", channel);
            }
            copy(p, b, "created_at", "transaction_at");
            return b;
        }
        if ("wallets.wallet_transaction.completed".equals(type) && p.hasNonNull("amount") && p.hasNonNull("currency")) {
            b.put("trigger", "WALLET_PAYMENT");
            b.put("source_type", "WALLET_TRANSACTION");
            b.put("amount", p.get("amount").asString());
            b.put("currency", p.get("currency").asString());
            b.put("channel", "WALLET");
            copy(p, b, "created_at", "transaction_at");
            return b;
        }
        return null;
    }

    private static void copy(JsonNode from, ObjectNode to, String src, String dst) {
        if (from.hasNonNull(src)) {
            to.put(dst, from.get(src).asString());
        }
    }

    private static String header(ConsumerRecord<String, String> r, String name) {
        Header h = r.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
