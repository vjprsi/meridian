package io.meridian.openbanking;

import io.meridian.common.error.ApiException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * In-memory deposit-service, ledger-service (balances) and payment-service behind the mocked ServiceClient.
 * Payments are idempotent by key; every call is recorded for assertions.
 */
public class FakeCore {
    public record Call(String service, String method, String path, JsonNode body, String key) {}

    private final JsonMapper json;
    private final Map<UUID, ObjectNode> accounts = new HashMap<>();
    private final Map<UUID, String> balances = new HashMap<>();
    private final Map<UUID, ObjectNode> payments = new HashMap<>();
    private final Map<String, ObjectNode> paymentsByKey = new HashMap<>();
    public final List<Call> calls = Collections.synchronizedList(new ArrayList<>());

    public FakeCore(JsonMapper json) {
        this.json = json;
    }

    public synchronized UUID account(UUID customerId, String currency, String status, String available) {
        UUID id = UUID.randomUUID();
        UUID ledger = UUID.randomUUID();
        ObjectNode a = json.createObjectNode();
        a.put("id", id.toString());
        a.put("customer_id", customerId.toString());
        a.put("currency", currency);
        a.put("status", status);
        a.put("ledger_account_id", ledger.toString());
        accounts.put(id, a);
        balances.put(ledger, available);
        return id;
    }

    public synchronized void setPaymentStatus(UUID paymentId, String status) {
        payments.get(paymentId).put("status", status);
    }

    public synchronized ObjectNode payment(UUID id) {
        return payments.get(id);
    }

    public List<Call> calls(String service, String method, String prefix) {
        synchronized (calls) {
            return calls.stream().filter(c -> c.service().equals(service) && c.method().equals(method)
                    && c.path().startsWith(prefix)).toList();
        }
    }

    public synchronized JsonNode get(String service, String path) {
        calls.add(new Call(service, "GET", path, null, null));
        String[] p = path.split("\\?")[0].split("/");
        switch (service) {
            case "deposit-service" -> {
                ObjectNode a = accounts.get(UUID.fromString(p[3]));
                if (a == null) {
                    throw ApiException.notFound("deposit account", p[3]);
                }
                return a;
            }
            case "ledger-service" -> {
                String bal = balances.get(UUID.fromString(p[3]));
                if (bal == null) {
                    throw ApiException.notFound("ledger account", p[3]);
                }
                ObjectNode b = json.createObjectNode();
                b.put("ledger_account_id", p[3]);
                b.put("available_balance", bal);
                b.put("posted_balance", bal);
                return b;
            }
            case "payment-service" -> {
                ObjectNode pay = payments.get(UUID.fromString(p[3]));
                if (pay == null) {
                    throw ApiException.notFound("payment", p[3]);
                }
                return pay;
            }
            default -> throw new IllegalArgumentException("unexpected GET " + service + path);
        }
    }

    public synchronized JsonNode post(String service, String path, Object body, String key) {
        JsonNode b = body == null ? json.createObjectNode() : json.valueToTree(body);
        calls.add(new Call(service, "POST", path, b, key));
        if (!"payment-service".equals(service)) {
            throw new IllegalArgumentException("unexpected POST " + service + path);
        }
        if (path.equals("/v1/payments")) {
            if (paymentsByKey.containsKey(key)) {
                return paymentsByKey.get(key);
            }
            ObjectNode pay = ((ObjectNode) b).deepCopy();
            UUID id = UUID.randomUUID();
            pay.put("id", id.toString());
            pay.put("status", b.hasNonNull("requested_execution_date") ? "APPROVED" : "SUBMITTED");
            pay.put("end_to_end_id", "E2E" + id.toString().substring(0, 8));
            payments.put(id, pay);
            paymentsByKey.put(key, pay);
            return pay;
        }
        if (path.endsWith("/cancel")) {
            ObjectNode pay = payments.get(UUID.fromString(path.split("/")[3]));
            if (pay == null) {
                throw ApiException.notFound("payment", path);
            }
            if (!List.of("APPROVED", "PENDING_APPROVAL", "INITIATED", "CANCELLED").contains(pay.get("status").asString())) {
                throw ApiException.conflict("invalid_transition", "payment cannot be cancelled");
            }
            pay.put("status", "CANCELLED");
            return pay;
        }
        throw new IllegalArgumentException("unexpected POST " + path);
    }
}
