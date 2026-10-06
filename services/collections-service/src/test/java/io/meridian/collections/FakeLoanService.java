package io.meridian.collections;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * In-memory stand-in for loan-service behind the mocked ServiceClient: loans and credit lines with status and
 * delinquency, repayments (incl. reversals), cursor-paged lists filtered by status. Records every request.
 */
public class FakeLoanService {
    private final JsonMapper json;
    private final Map<UUID, ObjectNode> loans = new LinkedHashMap<>();
    private final Map<UUID, ObjectNode> lines = new LinkedHashMap<>();
    private final Map<UUID, List<ObjectNode>> repayments = new HashMap<>();
    public final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    public FakeLoanService(JsonMapper json) {
        this.json = json;
    }

    public synchronized UUID loan(String type, UUID customer, int dpd, String overdue, String total) {
        UUID id = UUID.randomUUID();
        ObjectNode l = json.createObjectNode();
        l.put("id", id.toString());
        l.put("loan_number", "LN-" + id.toString().substring(0, 8));
        l.put("customer_id", customer.toString());
        l.put("product_id", UUID.nameUUIDFromBytes("product".getBytes()).toString());
        l.put("loan_type", type);
        l.put("currency", "EUR");
        l.put("risk_grade", "C2");
        loans.put(id, l);
        delinquency(id, dpd, overdue, total);
        return id;
    }

    public synchronized UUID creditLine(UUID customer, int dpd, String utilised) {
        UUID id = UUID.randomUUID();
        ObjectNode c = json.createObjectNode();
        c.put("id", id.toString());
        c.put("customer_id", customer.toString());
        c.put("product_id", UUID.nameUUIDFromBytes("card".getBytes()).toString());
        c.put("currency", "EUR");
        c.put("status", dpd > 0 ? "IN_ARREARS" : "ACTIVE");
        c.put("utilised_amount", utilised);
        c.put("outstanding_interest", "10.00");
        c.put("outstanding_fees", "5.00");
        c.put("days_past_due", dpd);
        lines.put(id, c);
        return id;
    }

    /** Sets DPD and amounts; status IN_ARREARS when past due, ACTIVE otherwise. */
    public synchronized void delinquency(UUID loan, int dpd, String overdue, String total) {
        ObjectNode l = loans.get(loan);
        l.put("days_past_due", dpd);
        l.put("overdue_amount", overdue);
        l.put("total_outstanding", total);
        l.put("status", dpd > 0 ? "IN_ARREARS" : "ACTIVE");
    }

    public synchronized void status(UUID id, String status) {
        (loans.containsKey(id) ? loans.get(id) : lines.get(id)).put("status", status);
    }

    public synchronized UUID repay(UUID loan, String amount, LocalDate valueDate) {
        UUID id = UUID.randomUUID();
        ObjectNode r = json.createObjectNode();
        r.put("id", id.toString());
        r.put("loan_id", loan.toString());
        r.put("amount", amount);
        r.put("currency", "EUR");
        r.put("source", "MANUAL");
        r.put("value_date", valueDate.toString());
        r.put("received_at", valueDate + "T10:00:00Z");
        r.put("status", "POSTED");
        repayments.computeIfAbsent(loan, k -> new ArrayList<>()).add(r);
        return id;
    }

    public synchronized void reverse(UUID loan, UUID repayment, LocalDate valueDate) {
        ObjectNode original = repayments.get(loan).stream()
                .filter(r -> r.get("id").asString().equals(repayment.toString())).findFirst().orElseThrow();
        ObjectNode r = original.deepCopy();
        r.put("id", UUID.randomUUID().toString());
        r.put("status", "REVERSED");
        r.put("reversal_of_id", repayment.toString());
        r.put("value_date", valueDate.toString());
        repayments.get(loan).add(r);
    }

    public synchronized JsonNode get(String service, String path) {
        calls.add("GET " + path);
        if (!"loan-service".equals(service)) {
            throw new IllegalArgumentException("unexpected service " + service);
        }
        String[] pq = path.split("\\?", 2);
        Map<String, String> q = new HashMap<>();
        if (pq.length > 1) {
            for (String kv : pq[1].split("&")) {
                String[] x = kv.split("=", 2);
                q.put(x[0], java.net.URLDecoder.decode(x[1], java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        String[] p = pq[0].split("/");
        if (pq[0].equals("/v1/loans")) {
            return page(loans.values().stream().filter(l -> l.get("status").asString().equals(q.get("status"))).toList(), q);
        }
        if (pq[0].equals("/v1/credit-lines")) {
            return page(lines.values().stream().filter(l -> l.get("status").asString().equals(q.get("status"))).toList(), q);
        }
        if (pq[0].equals("/v1/loan-repayments")) {
            UUID line = UUID.fromString(q.get("credit_line_id"));
            return page(repayments.getOrDefault(line, List.of()), q);
        }
        if (p.length == 4 && p[2].equals("loans")) {
            ObjectNode l = loans.get(UUID.fromString(p[3]));
            if (l == null) {
                throw ApiException.notFound("loan", p[3]);
            }
            return l;
        }
        if (p.length == 4 && p[2].equals("credit-lines")) {
            ObjectNode l = lines.get(UUID.fromString(p[3]));
            if (l == null) {
                throw ApiException.notFound("credit line", p[3]);
            }
            return l;
        }
        if (p.length == 5 && p[2].equals("loans") && p[4].equals("repayments")) {
            return page(repayments.getOrDefault(UUID.fromString(p[3]), List.of()), q);
        }
        throw new IllegalArgumentException("unexpected GET " + path);
    }

    /** Cursor = index of the next row, so multi-page reads are exercised with small limits. */
    private ObjectNode page(List<ObjectNode> all, Map<String, String> q) {
        int limit = Integer.parseInt(q.getOrDefault("limit", "50"));
        int from = q.containsKey("cursor") ? Integer.parseInt(q.get("cursor")) : 0;
        ObjectNode page = json.createObjectNode();
        ArrayNode data = page.putArray("data");
        int to = Math.min(all.size(), from + limit);
        for (int i = from; i < to; i++) {
            data.add(all.get(i));
        }
        boolean more = to < all.size();
        page.put("has_more", more);
        if (more) {
            page.put("next_cursor", String.valueOf(to));
        } else {
            page.putNull("next_cursor");
        }
        return page;
    }

    public long calls(String prefix) {
        synchronized (calls) {
            return calls.stream().filter(c -> c.startsWith(prefix)).count();
        }
    }

    static BigDecimal dec(JsonNode n, String f) {
        return new BigDecimal(n.get(f).asString());
    }
}
