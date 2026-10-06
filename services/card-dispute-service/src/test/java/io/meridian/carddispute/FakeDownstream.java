package io.meridian.carddispute;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * In-memory card-service and ledger-service behind the mocked ServiceClient: cards by id, balanced journal entries
 * idempotent by key, reversals (once per entry), and a net balance per ledger account (credits positive, debits
 * negative) so tests can assert that dispute postings net out. Every call is recorded.
 */
public class FakeDownstream {
    public record Call(String service, String method, String path, JsonNode body, String key) {}

    private final JsonMapper json;
    private final Map<UUID, ObjectNode> cards = new HashMap<>();
    private final Map<String, ObjectNode> byKey = new HashMap<>();
    private final Map<UUID, ObjectNode> journals = new HashMap<>();
    private final Map<UUID, UUID> reversals = new HashMap<>();
    private final Map<UUID, BigDecimal> balances = new HashMap<>();
    public final List<Call> calls = Collections.synchronizedList(new ArrayList<>());

    public FakeDownstream(JsonMapper json) {
        this.json = json;
    }

    public synchronized void addCard(UUID cardId, UUID customerId, UUID ledgerAccountId) {
        ObjectNode c = json.createObjectNode();
        c.put("id", cardId.toString());
        c.put("customer_id", customerId.toString());
        c.put("ledger_account_id", ledgerAccountId.toString());
        c.put("status", "ACTIVE");
        c.put("last4", "4242");
        cards.put(cardId, c);
    }

    public synchronized JsonNode get(String service, String path) {
        calls.add(new Call(service, "GET", path, null, null));
        if ("card-service".equals(service) && path.startsWith("/v1/cards/")) {
            ObjectNode c = cards.get(UUID.fromString(path.substring("/v1/cards/".length())));
            if (c == null) {
                throw new ApiException(404, "not_found", "card-service: card was not found");
            }
            return c;
        }
        throw new IllegalArgumentException("unexpected GET " + service + " " + path);
    }

    public synchronized JsonNode post(String service, String path, Object body, String key) {
        JsonNode b = body == null ? json.createObjectNode() : json.valueToTree(body);
        calls.add(new Call(service, "POST", path, b, key));
        if (!"ledger-service".equals(service)) {
            throw new IllegalArgumentException("unexpected POST " + service + " " + path);
        }
        if (key == null || key.isBlank() || key.length() > 128) {
            throw ApiException.badRequest("idempotency_key_required", "bad key");
        }
        if (byKey.containsKey(key)) {
            return byKey.get(key);
        }
        if (path.equals("/v1/journal-entries")) {
            return post(b, key);
        }
        if (path.startsWith("/v1/journal-entries/") && path.endsWith("/reverse")) {
            UUID id = UUID.fromString(path.split("/")[3]);
            if (reversals.containsKey(id)) {
                throw ApiException.conflict("already_reversed", "journal entry already reversed");
            }
            ObjectNode original = journals.get(id);
            if (original == null) {
                throw ApiException.notFound("journal entry", id);
            }
            ObjectNode rev = json.createObjectNode();
            rev.put("ledger_id", original.get("ledger_id").asString());
            rev.put("entry_type", "REVERSAL");
            rev.put("description", "Reversal of " + id);
            ArrayNode lines = rev.putArray("entries");
            for (JsonNode e : original.get("entries")) {
                lines.addObject().put("ledger_account_id", e.get("ledger_account_id").asString())
                        .put("direction", "DEBIT".equals(e.get("direction").asString()) ? "CREDIT" : "DEBIT")
                        .put("amount", e.get("amount").asString()).put("currency", e.get("currency").asString());
            }
            ObjectNode out = post(rev, key);
            out.put("reversal_of_id", id.toString());
            reversals.put(id, UUID.fromString(out.get("id").asString()));
            return out;
        }
        throw new IllegalArgumentException("unexpected POST " + path);
    }

    private ObjectNode post(JsonNode b, String key) {
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        if (!b.hasNonNull("ledger_id")) {
            throw ApiException.unprocessable("ledger_required", "ledger_id missing");
        }
        if (b.get("entries").isEmpty()) {
            throw ApiException.unprocessable("no_entries", "journal entry without lines");
        }
        for (JsonNode e : b.get("entries")) {
            BigDecimal amt = new BigDecimal(e.get("amount").asString());
            if (amt.signum() <= 0) {
                throw ApiException.unprocessable("invalid_amount", "non-positive line");
            }
            if ("DEBIT".equals(e.get("direction").asString())) {
                debits = debits.add(amt);
            } else {
                credits = credits.add(amt);
            }
        }
        if (debits.compareTo(credits) != 0) {
            throw ApiException.unprocessable("unbalanced_entry", "debits " + debits + " != credits " + credits);
        }
        for (JsonNode e : b.get("entries")) {
            BigDecimal amt = new BigDecimal(e.get("amount").asString());
            balances.merge(UUID.fromString(e.get("ledger_account_id").asString()),
                    "DEBIT".equals(e.get("direction").asString()) ? amt.negate() : amt, BigDecimal::add);
        }
        ObjectNode entry = ((ObjectNode) b).deepCopy();
        UUID id = UUID.randomUUID();
        entry.put("id", id.toString());
        entry.put("idempotency_key", key);
        journals.put(id, entry);
        byKey.put(key, entry);
        return entry;
    }

    /** Net balance: credits minus debits. */
    public synchronized BigDecimal balance(UUID account) {
        return balances.getOrDefault(account, BigDecimal.ZERO);
    }

    public synchronized JsonNode journal(UUID id) {
        return journals.get(id);
    }

    public List<Call> calls(String method, String pathPrefix) {
        synchronized (calls) {
            return calls.stream().filter(c -> c.method().equals(method) && c.path().startsWith(pathPrefix)).toList();
        }
    }
}
