package io.meridian.routing;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Stateful stand-ins for the services routing-service calls through the mocked ServiceClient: ledger-service
 * (idempotent journal entries, balances moved by postings), payment-service (payments by end_to_end_id, status
 * reports, inbound credits, recalls, returns) and reference-data-service (weekends + holidays calendar).
 */
class FakeDownstream {
    record Call(String service, String method, String path, JsonNode body, String key) {}

    private final JsonMapper json;
    final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    final Map<String, ObjectNode> journalsByKey = new HashMap<>();
    final Map<UUID, BigDecimal> balances = new HashMap<>();
    final Map<String, UUID> paymentsByE2e = new HashMap<>();
    final Map<String, JsonNode> processedByKey = new HashMap<>();
    final Set<LocalDate> holidays = new HashSet<>();

    FakeDownstream(JsonMapper json) {
        this.json = json;
    }

    synchronized JsonNode post(String service, String path, Object body, String key) {
        JsonNode b = body == null ? json.createObjectNode() : json.valueToTree(body);
        calls.add(new Call(service, "POST", path, b, key));
        if (key == null || key.isBlank()) {
            throw ApiException.badRequest("idempotency_key_required", "missing key");
        }
        if (processedByKey.containsKey(service + key)) {
            return processedByKey.get(service + key);
        }
        JsonNode result = switch (service) {
            case "ledger-service" -> journal(b, key);
            case "payment-service" -> payments(path, b);
            default -> throw new IllegalArgumentException("unexpected POST " + service + path);
        };
        processedByKey.put(service + key, result);
        return result;
    }

    synchronized JsonNode get(String service, String path) {
        if ("business-day-service".equals(service)) {
            return null; // no business day opened: the service clock / database date applies
        }
        calls.add(new Call(service, "GET", path, null, null));
        String[] pq = path.split("\\?", 2);
        Map<String, String> q = new HashMap<>();
        if (pq.length > 1) {
            for (String kv : pq[1].split("&")) {
                String[] x = kv.split("=", 2);
                q.put(x[0], URLDecoder.decode(x[1], StandardCharsets.UTF_8));
            }
        }
        String[] p = pq[0].split("/");
        switch (service) {
            case "ledger-service" -> {
                UUID acct = UUID.fromString(p[3]);
                ObjectNode n = json.createObjectNode();
                n.put("ledger_account_id", acct.toString());
                n.put("posted_balance", balances.getOrDefault(acct, BigDecimal.ZERO).toPlainString());
                n.put("available_balance", balances.getOrDefault(acct, BigDecimal.ZERO).toPlainString());
                return n;
            }
            case "payment-service" -> {
                ObjectNode page = json.createObjectNode();
                var data = page.putArray("data");
                UUID id = paymentsByE2e.get(q.get("end_to_end_id"));
                if (id != null) {
                    data.addObject().put("id", id.toString()).put("end_to_end_id", q.get("end_to_end_id"))
                            .put("status", "SUBMITTED");
                }
                page.put("has_more", false);
                return page;
            }
            case "reference-data-service" -> {
                LocalDate d = LocalDate.parse(q.get("date"));
                ObjectNode n = json.createObjectNode();
                if (pq[0].endsWith("/is-business-day")) {
                    n.put("date", d.toString());
                    n.put("is_business_day", business(d));
                } else {
                    LocalDate next = d.plusDays(1);
                    while (!business(next)) {
                        next = next.plusDays(1);
                    }
                    n.put("business_day", next.toString());
                }
                return n;
            }
            default -> throw new IllegalArgumentException("unexpected GET " + service + path);
        }
    }

    private boolean business(LocalDate d) {
        return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY && !holidays.contains(d);
    }

    private JsonNode journal(JsonNode b, String key) {
        if (journalsByKey.containsKey(key)) {
            return journalsByKey.get(key);
        }
        BigDecimal dr = BigDecimal.ZERO;
        BigDecimal cr = BigDecimal.ZERO;
        for (JsonNode e : b.get("entries")) {
            BigDecimal a = new BigDecimal(e.get("amount").asString());
            UUID acct = UUID.fromString(e.get("ledger_account_id").asString());
            if ("DEBIT".equals(e.get("direction").asString())) {
                dr = dr.add(a);
                balances.merge(acct, a, BigDecimal::add);
            } else {
                cr = cr.add(a);
                balances.merge(acct, a.negate(), BigDecimal::add);
            }
        }
        if (dr.compareTo(cr) != 0) {
            throw ApiException.unprocessable("unbalanced_entry", "unbalanced");
        }
        ObjectNode je = ((ObjectNode) b).deepCopy();
        je.put("id", UUID.randomUUID().toString());
        journalsByKey.put(key, je);
        return je;
    }

    private JsonNode payments(String path, JsonNode b) {
        ObjectNode r = json.createObjectNode();
        r.put("id", UUID.randomUUID().toString());
        if (path.endsWith("/status-report")) {
            r.put("id", path.split("/")[3]);
            r.put("status", "ACSC".equals(b.path("status").asString()) ? "SETTLED" : "ACCEPTED");
        } else if (path.equals("/v1/payment-recalls/inbound") || path.equals("/v1/payment-returns/inbound")) {
            UUID pay = paymentsByE2e.get(b.path("original_end_to_end_id").asString());
            if (pay == null) {
                throw ApiException.notFound("payment", b.path("original_end_to_end_id").asString());
            }
            r.put("payment_id", pay.toString());
        } else if (!path.equals("/v1/payments/inbound")) {
            throw new IllegalArgumentException("unexpected payment-service POST " + path);
        }
        return r;
    }

    List<Call> calls(String service, String pathPart) {
        synchronized (calls) {
            return calls.stream().filter(c -> c.service().equals(service) && c.path().contains(pathPart)).toList();
        }
    }
}
