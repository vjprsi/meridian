package io.meridian.cardclearing.infrastructure;

import io.meridian.cardclearing.domain.ClearingMatcher.AuthView;
import io.meridian.common.error.ApiException;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Adapters to card-authorization-service, card-service and ledger-service over {@link ServiceClient}
 * (tenant, user and token propagated). Every write carries a deterministic idempotency key.
 */
@Component
public class Clients {
    static final String AUTH = "card-authorization-service";
    static final String CARDS = "card-service";
    static final String LEDGER = "ledger-service";

    private final ServiceClient client;
    private final JsonMapper json;

    public Clients(ServiceClient client, JsonMapper json) {
        this.client = client;
        this.json = json;
    }

    // ------------------------------------------------------------------ authorisations

    /** Authorisations matching the given list filters (name, value pairs). */
    public List<AuthView> findAuthorizations(String... filters) {
        StringBuilder q = new StringBuilder("/v1/card-authorizations?limit=50");
        for (int i = 0; i < filters.length; i += 2) {
            q.append('&').append(filters[i]).append('=').append(URLEncoder.encode(filters[i + 1], StandardCharsets.UTF_8));
        }
        List<AuthView> out = new ArrayList<>();
        for (JsonNode n : client.get(AUTH, q.toString()).path("data")) {
            out.add(auth(n));
        }
        return out;
    }

    public Optional<AuthView> authorization(UUID id) {
        try {
            return Optional.of(auth(client.get(AUTH, "/v1/card-authorizations/" + id)));
        } catch (ApiException e) {
            if (e.status() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    public void clearAuthorization(UUID authId, BigDecimal amount, UUID recordId, boolean fin) {
        ObjectNode body = json.createObjectNode();
        body.put("amount", amount.toPlainString());
        body.put("clearing_record_id", recordId.toString());
        body.put("final", fin);
        client.post(AUTH, "/v1/card-authorizations/" + authId + "/clear", body, "clear:" + recordId);
    }

    static AuthView auth(JsonNode n) {
        return new AuthView(uuid(n, "id"), uuid(n, "card_id"), uuid(n, "card_program_id"),
                uuid(n, "parent_authorization_id"), text(n, "message_type"), text(n, "status"),
                text(n, "billing_currency"), dec(n, "billing_amount"), dec(n, "approved_amount"),
                dec(n, "cleared_amount"), dec(n, "reversed_amount"), uuid(n, "hold_id"), uuid(n, "hold_account_id"),
                dec(n, "fx_markup"));
    }

    // ------------------------------------------------------------------ cards

    /** Card fields clearing needs: ledger_account_id, card_program_id, customer_id; empty if unknown. */
    public Optional<JsonNode> card(UUID cardId) {
        try {
            return Optional.of(client.get(CARDS, "/v1/cards/" + cardId));
        } catch (ApiException e) {
            if (e.status() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    // ------------------------------------------------------------------ ledger

    public record Leg(UUID account, String direction, BigDecimal amount, String description) {}

    /** Captures a hold into postings against the counter account; returns the capture journal entry id. */
    public UUID capture(UUID holdId, BigDecimal amount, UUID counterAccount, String description,
                        boolean releaseRemainder, String key) {
        ObjectNode body = json.createObjectNode();
        body.put("amount", amount.toPlainString());
        body.put("counter_account_id", counterAccount.toString());
        body.put("description", description);
        body.put("release_remainder", releaseRemainder);
        JsonNode hold = client.post(LEDGER, "/v1/holds/" + holdId + "/capture", body, key);
        UUID journal = uuid(hold, "journal_entry_id");
        if (journal != null) {
            return journal;
        }
        // ledger-service returns the hold; the capture posting is recorded on the hold's events
        UUID last = null;
        for (JsonNode e : client.get(LEDGER, "/v1/holds/" + holdId + "/events?limit=200").path("data")) {
            String type = text(e, "event_type");
            if (("CAPTURED".equals(type) || "PARTIALLY_CAPTURED".equals(type)) && uuid(e, "journal_entry_id") != null) {
                last = last == null ? uuid(e, "journal_entry_id") : last;
            }
        }
        return last;
    }

    public UUID post(UUID ledgerId, String entryType, String description, String currency, String sourceType,
                     UUID sourceId, String reference, List<Leg> legs, String key) {
        ObjectNode body = json.createObjectNode();
        body.put("ledger_id", ledgerId.toString());
        body.put("entry_type", entryType);
        body.put("description", trim(description, 500));
        body.put("source_type", sourceType);
        if (sourceId != null) {
            body.put("source_id", sourceId.toString());
        }
        if (reference != null) {
            body.put("reference", trim(reference, 128));
        }
        ArrayNode entries = body.putArray("entries");
        for (Leg l : legs) {
            if (l.account() == null) {
                throw new ApiException(500, "ledger_account_not_configured",
                        "A ledger account required for this posting is not configured");
            }
            ObjectNode e = entries.addObject();
            e.put("ledger_account_id", l.account().toString());
            e.put("direction", l.direction());
            e.put("amount", l.amount().toPlainString());
            e.put("currency", currency);
            if (l.description() != null) {
                e.put("description", trim(l.description(), 500));
            }
        }
        return uuid(client.post(LEDGER, "/v1/journal-entries", body, key), "id");
    }

    public UUID reverse(UUID journalEntryId, String reason, String key) {
        ObjectNode body = json.createObjectNode();
        body.put("reason", trim(reason, 255));
        return uuid(client.post(LEDGER, "/v1/journal-entries/" + journalEntryId + "/reverse", body, key), "id");
    }

    // ------------------------------------------------------------------ helpers

    static String trim(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    static String text(JsonNode n, String f) {
        JsonNode v = n == null ? null : n.get(f);
        return v == null || v.isNull() ? null : v.asString();
    }

    static UUID uuid(JsonNode n, String f) {
        String s = text(n, f);
        return s == null || s.isBlank() ? null : UUID.fromString(s);
    }

    static BigDecimal dec(JsonNode n, String f) {
        String s = text(n, f);
        return s == null || s.isBlank() ? null : new BigDecimal(s);
    }
}
