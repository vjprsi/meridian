package io.meridian.rewards;

import io.meridian.common.error.ApiException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * In-memory stand-ins for ledger-service (journal entries idempotent by key, reversals, a switchable outage) and
 * deposit-service (account lookups) behind the mocked ServiceClient. Every call is recorded.
 */
public class FakeServices {
    public record Call(String service, String method, String path, JsonNode body, String key) {}

    private final JsonMapper json;
    private final Map<String, ObjectNode> journalsByKey = new HashMap<>();
    private final Map<UUID, ObjectNode> accounts = new HashMap<>();
    public final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    public final AtomicBoolean ledgerDown = new AtomicBoolean(false);

    public FakeServices(JsonMapper json) {
        this.json = json;
    }

    /** Registers a deposit account and returns its ledger account id. */
    public synchronized UUID depositAccount(UUID id, String currency) {
        UUID ledgerAccount = UUID.randomUUID();
        ObjectNode a = json.createObjectNode();
        a.put("id", id.toString());
        a.put("ledger_account_id", ledgerAccount.toString());
        a.put("currency", currency);
        a.put("status", "ACTIVE");
        accounts.put(id, a);
        return ledgerAccount;
    }

    public UUID ledgerAccountOf(UUID depositAccount) {
        return UUID.fromString(accounts.get(depositAccount).get("ledger_account_id").asString());
    }

    public synchronized JsonNode get(String service, String path) {
        if ("business-day-service".equals(service)) {
            return null; // no business day opened: the database date applies
        }
        calls.add(new Call(service, "GET", path, null, null));
        if ("deposit-service".equals(service) && path.startsWith("/v1/deposit-accounts/")) {
            UUID id = UUID.fromString(path.substring("/v1/deposit-accounts/".length()));
            ObjectNode a = accounts.get(id);
            if (a == null) {
                throw ApiException.notFound("deposit account", id);
            }
            return a;
        }
        throw new IllegalArgumentException("unexpected GET " + service + " " + path);
    }

    public synchronized JsonNode post(String service, String path, Object body, String key) {
        JsonNode b = body == null ? json.createObjectNode() : json.valueToTree(body);
        calls.add(new Call(service, "POST", path, b, key));
        if (!"ledger-service".equals(service)) {
            throw new IllegalArgumentException("unexpected POST " + service + " " + path);
        }
        if (ledgerDown.get()) {
            throw new ApiException(422, "account_frozen", "ledger-service: credit account is frozen");
        }
        if (journalsByKey.containsKey(key)) {
            return journalsByKey.get(key);
        }
        ObjectNode j = json.createObjectNode();
        j.put("id", UUID.randomUUID().toString());
        j.put("status", "POSTED");
        if (path.equals("/v1/journal-entries")) {
            j.set("entries", b.get("entries"));
        } else if (path.startsWith("/v1/journal-entries/") && path.endsWith("/reverse")) {
            j.put("reversal_of_id", path.split("/")[3]);
        } else {
            throw new IllegalArgumentException("unexpected POST " + path);
        }
        journalsByKey.put(key, j);
        return j;
    }

    public List<Call> postings() {
        synchronized (calls) {
            return calls.stream().filter(c -> "POST".equals(c.method()) && "/v1/journal-entries".equals(c.path())).toList();
        }
    }

    public Call lastPosting() {
        return postings().getLast();
    }
}
