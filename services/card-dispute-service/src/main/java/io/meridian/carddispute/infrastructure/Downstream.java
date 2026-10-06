package io.meridian.carddispute.infrastructure;

import io.meridian.carddispute.config.DisputeProperties;
import io.meridian.common.error.ApiException;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** card-service and ledger-service over {@link ServiceClient} (tenant, user and token propagated). */
@Component
public class Downstream {
    static final String CARDS = "card-service";
    static final String LEDGER = "ledger-service";

    private final ServiceClient client;
    private final DisputeProperties props;
    private final JsonMapper json;

    public Downstream(ServiceClient client, DisputeProperties props, JsonMapper json) {
        this.client = client;
        this.props = props;
        this.json = json;
    }

    /** The card (customer_id, ledger_account_id ...); 422 card_not_found when card-service does not know it. */
    public JsonNode card(UUID cardId) {
        try {
            return client.get(CARDS, "/v1/cards/" + cardId);
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw ApiException.unprocessable("card_not_found", "card " + cardId + " was not found");
            }
            throw e;
        }
    }

    public record Leg(UUID account, String direction, BigDecimal amount, String description) {}

    /** Posts a balanced journal entry (zero legs dropped); returns its id. */
    public UUID post(String key, String description, String currency, UUID disputeId, List<Leg> legs) {
        ObjectNode body = json.createObjectNode();
        body.put("ledger_id", required(props.ledgerId(), "meridian.cards.ledger-id").toString());
        body.put("entry_type", "ADJUSTMENT");
        body.put("description", description);
        body.put("source_type", "CARD_DISPUTE");
        body.put("source_id", disputeId.toString());
        body.put("reference", disputeId.toString());
        ArrayNode entries = body.putArray("entries");
        for (Leg l : legs) {
            if (l.amount() == null || l.amount().signum() == 0) {
                continue;
            }
            ObjectNode e = entries.addObject();
            e.put("ledger_account_id", required(l.account(), "ledger account").toString());
            e.put("direction", l.direction());
            e.put("amount", l.amount().toPlainString());
            e.put("currency", currency);
            e.put("description", l.description());
        }
        return UUID.fromString(client.post(LEDGER, "/v1/journal-entries", body, key(key)).get("id").asString());
    }

    public UUID reverse(UUID entryId, String reason, String key) {
        ObjectNode body = json.createObjectNode();
        body.put("reason", reason.length() > 255 ? reason.substring(0, 255) : reason);
        return UUID.fromString(client.post(LEDGER, "/v1/journal-entries/" + entryId + "/reverse", body, key(key))
                .get("id").asString());
    }

    public UUID suspense() {
        return required(props.dispute().suspenseAccount(), "meridian.cards.dispute.suspense-account");
    }

    public UUID lossExpense() {
        return required(props.dispute().lossExpenseAccount(), "meridian.cards.dispute.loss-expense-account");
    }

    public UUID schemeReceivable() {
        return required(props.dispute().schemeReceivableAccount(), "meridian.cards.dispute.scheme-receivable-account");
    }

    private static <T> T required(T v, String what) {
        if (v == null) {
            throw new ApiException(500, "ledger_account_not_configured", what + " is not configured");
        }
        return v;
    }

    /** Ledger keys are at most 128 characters; longer ones are hashed. */
    static String key(String raw) {
        if (raw.length() <= 128) {
            return raw;
        }
        try {
            return "h:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
