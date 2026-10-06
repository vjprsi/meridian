package io.meridian.collateral;

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
import tools.jackson.databind.node.ObjectNode;

/**
 * In-memory stand-in for loan-service behind the mocked ServiceClient: loans and credit lines with outstanding
 * amounts, repayments that reduce them, post-write-off recoveries, idempotent by key. Every call is recorded.
 */
public class FakeLoanService {
    public record Call(String method, String path, JsonNode body, String key) {}

    private final JsonMapper json;
    private final Map<UUID, ObjectNode> loans = new HashMap<>();
    private final Map<UUID, ObjectNode> lines = new HashMap<>();
    private final Map<String, JsonNode> byKey = new HashMap<>();
    public final List<Call> calls = Collections.synchronizedList(new ArrayList<>());

    public FakeLoanService(JsonMapper json) {
        this.json = json;
    }

    public synchronized UUID loan(String currency, String outstanding, String status) {
        UUID id = UUID.randomUUID();
        ObjectNode l = json.createObjectNode();
        l.put("id", id.toString());
        l.put("customer_id", UUID.randomUUID().toString());
        l.put("currency", currency);
        l.put("status", status);
        l.put("total_outstanding", outstanding);
        loans.put(id, l);
        return id;
    }

    public synchronized UUID creditLine(String currency, String utilised) {
        UUID id = UUID.randomUUID();
        ObjectNode l = json.createObjectNode();
        l.put("id", id.toString());
        l.put("customer_id", UUID.randomUUID().toString());
        l.put("currency", currency);
        l.put("status", "ACTIVE");
        l.put("utilised_amount", utilised);
        l.put("instalment_balance", "0");
        l.put("outstanding_interest", "0");
        l.put("outstanding_fees", "0");
        lines.put(id, l);
        return id;
    }

    public synchronized void setOutstanding(UUID loan, String amount) {
        loans.get(loan).put("total_outstanding", amount);
    }

    public synchronized void setStatus(UUID loan, String status) {
        loans.get(loan).put("status", status);
    }

    public synchronized BigDecimal outstanding(UUID loan) {
        return new BigDecimal(loans.get(loan).get("total_outstanding").asString());
    }

    public List<Call> calls(String method, String prefix) {
        synchronized (calls) {
            return calls.stream().filter(c -> c.method().equals(method) && c.path().startsWith(prefix)).toList();
        }
    }

    public synchronized JsonNode get(String service, String path) {
        calls.add(new Call("GET", path, null, null));
        if (!"loan-service".equals(service)) {
            throw new IllegalArgumentException("unexpected service " + service);
        }
        String[] p = path.split("\\?")[0].split("/");
        UUID id = UUID.fromString(p[3]);
        ObjectNode row = switch (p[2]) {
            case "loans" -> loans.get(id);
            case "credit-lines" -> lines.get(id);
            default -> throw new IllegalArgumentException(path);
        };
        if (row == null) {
            throw ApiException.notFound(p[2], id);
        }
        return row.deepCopy();
    }

    public synchronized JsonNode post(String service, String path, Object body, String key) {
        JsonNode b = json.valueToTree(body);
        calls.add(new Call("POST", path, b, key));
        if (key == null || key.length() > 128) {
            throw ApiException.badRequest("idempotency_key_required", "bad key");
        }
        if (byKey.containsKey(key)) {
            return byKey.get(key);
        }
        BigDecimal amount = new BigDecimal(b.get("amount").asString());
        String[] p = path.split("/");
        ObjectNode result = json.createObjectNode();
        result.put("id", UUID.randomUUID().toString());
        result.put("amount", amount.toPlainString());
        if (path.equals("/v1/loan-recoveries")) {
            ObjectNode loan = loans.get(UUID.fromString(b.get("loan_id").asString()));
            if (!"WRITTEN_OFF".equals(loan.get("status").asString())) {
                throw ApiException.conflict("loan_not_written_off", "not written off");
            }
            result.put("source", b.get("source").asString());
        } else if (p[2].equals("loans") && p[4].equals("repay")) {
            ObjectNode loan = loans.get(UUID.fromString(p[3]));
            BigDecimal out = new BigDecimal(loan.get("total_outstanding").asString());
            if (amount.compareTo(out) > 0) {
                throw ApiException.unprocessable("overpayment", "more than outstanding");
            }
            loan.put("total_outstanding", out.subtract(amount).toPlainString());
            result.put("source", b.get("source").asString());
        } else if (p[2].equals("credit-lines") && p[4].equals("repay")) {
            ObjectNode line = lines.get(UUID.fromString(p[3]));
            line.put("utilised_amount", new BigDecimal(line.get("utilised_amount").asString()).subtract(amount).toPlainString());
        } else {
            throw new IllegalArgumentException("unexpected POST " + path);
        }
        byKey.put(key, result);
        return result;
    }
}
