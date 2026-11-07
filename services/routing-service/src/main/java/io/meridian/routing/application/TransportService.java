package io.meridian.routing.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.routing.domain.BankCodes;
import io.meridian.routing.infrastructure.Downstream;
import io.meridian.routing.infrastructure.Rows;
import io.meridian.routing.infrastructure.rails.RailTransport;
import io.meridian.routing.infrastructure.rails.RailTransport.Kind;
import io.meridian.routing.infrastructure.rails.RailTransport.Node;
import io.meridian.routing.infrastructure.rails.RailTransport.Outcome;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Network side of the rails: sends stored outbound messages through the rail's access nodes, resolves timeouts and
 * serves the rail key directory.
 *
 * <p><b>Smart routing / failover.</b> A rail's {@code rail_adapter} rows whose {@code config.protocol} has a transport
 * are its access nodes. They are ranked by observed health (ACTIVE before DEGRADED, fewer consecutive failures),
 * then the primary flag, observed latency and configured cost. A node that cannot be reached (connection refused,
 * 5xx, node down) is skipped for the next; after three consecutive failures it is marked DEGRADED and drops in rank,
 * and the first success restores it. After a TIMEOUT (the rail may have the transfer) the other nodes are asked for
 * its status before anything is resent; resending is safe because the rail is idempotent on end_to_end_id.
 *
 * <p><b>Append-only.</b> rail_message rows are immutable: the outbound pacs.008 stays SENT; the rail's answer is
 * stored as an INBOUND pacs.002 row (raw payload archived). An outbound message is final when a pacs.002 for its
 * end_to_end_id exists, pending otherwise. The API views derive ACKNOWLEDGED / NACKED from that answer.
 */
@Service
public class TransportService {
    private static final int DEGRADE_AFTER = 3;

    private final Rows rows;
    private final Events events;
    private final Downstream downstream;
    private final TransactionTemplate tx;
    private final Map<String, RailTransport> transports = new HashMap<>();

    public TransportService(Rows rows, Events events, Downstream downstream, List<RailTransport> transports,
                            TransactionTemplate tx) {
        this.rows = rows;
        this.tx = tx;
        this.events = events;
        this.downstream = downstream;
        transports.forEach(t -> this.transports.put(t.protocol(), t));
    }

    /** A ranked access node with the transport that speaks its protocol. */
    record Ranked(Node node, RailTransport transport, String status, boolean primary, int failures, long latency,
                  double cost) {}

    record Answer(Ranked node, Outcome outcome) {}

    // ================================================================== nodes

    List<Ranked> nodes(UUID railId) {
        List<Ranked> out = new ArrayList<>();
        for (ObjectNode a : rows.query("select row_to_json(t)::text from rail_adapter t where payment_rail_id = ? "
                + "and status in ('ACTIVE', 'DEGRADED')", railId)) {
            JsonNode cfg = a.path("config");
            RailTransport t = transports.get(cfg.path("protocol").asString(""));
            String endpoint = text(a, "endpoint_url");
            if (t == null || endpoint == null) {
                continue;
            }
            JsonNode last = a.path("last_test_result");
            Node n = new Node(UUID.fromString(a.get("id").asString()), text(a, "name"), endpoint,
                    text(a, "participant_id"), cfg);
            out.add(new Ranked(n, t, text(a, "status"), a.path("is_primary").asBoolean(false),
                    last.path("failures").asInt(0), last.path("latency_ms").asLong(cfg.path("latency_ms").asLong(200)),
                    cfg.path("cost").asDouble(0)));
        }
        out.sort(Comparator.comparing((Ranked r) -> "ACTIVE".equals(r.status()) ? 0 : 1)
                .thenComparingInt(Ranked::failures)
                .thenComparing(r -> r.primary() ? 0 : 1)
                .thenComparingLong(Ranked::latency)
                .thenComparingDouble(Ranked::cost));
        return out;
    }

    /** True when the rail has at least one network node (else its status comes from callbacks / operations). */
    public boolean networked(UUID railId) {
        return !nodes(railId).isEmpty();
    }

    private void healthy(Ranked r, long ms) {
        ObjectNode res = rows.object();
        res.put("ok", true);
        res.put("latency_ms", ms);
        res.put("failures", 0);
        res.put("source", "transport");
        rows.execute("update rail_adapter set last_heartbeat_at = now(), last_test_result = ?::jsonb, "
                + "status = case when status = 'DEGRADED' then 'ACTIVE' else status end where id = ?",
                rows.json().writeValueAsString(res), r.node().adapterId());
    }

    private void failed(Ranked r, String detail) {
        int failures = r.failures() + 1;
        ObjectNode res = rows.object();
        res.put("ok", false);
        res.put("error", detail == null ? "unreachable" : detail);
        res.put("failures", failures);
        res.put("latency_ms", r.latency());
        res.put("source", "transport");
        rows.execute("update rail_adapter set last_test_result = ?::jsonb"
                + (failures >= DEGRADE_AFTER ? ", status = case when status = 'ACTIVE' then 'DEGRADED' else status end" : "")
                + " where id = ?", rows.json().writeValueAsString(res), r.node().adapterId());
    }

    // ================================================================== outbound

    /**
     * Sends a stored outbound pacs.008 through the rail's best node, failing over as described above. Returns the
     * message view: ACKNOWLEDGED (rail accepted, final for a real-time rail), NACKED (rail rejected; {@code error}
     * = "RJCT &lt;code&gt;: &lt;reason&gt;") or SENT (pending - resolve with query-status). Throws 503
     * rail_unreachable when no node took the message (the payment stays deferred and can be resubmitted).
     * A rail without network nodes returns the message unchanged.
     */
    public ObjectNode dispatch(ObjectNode msg, JsonNode payment) {
        if (!"OUTBOUND".equals(text(msg, "direction"))) {
            return msg;
        }
        UUID railId = UUID.fromString(msg.get("payment_rail_id").asString());
        String e2e = text(msg, "end_to_end_id");
        Optional<ObjectNode> prior = answerOf(railId, e2e);
        if (prior.isPresent()) {
            return view(msg, prior.get());
        }
        List<Ranked> nodes = nodes(railId);
        if (nodes.isEmpty()) {
            return msg;
        }
        RailTransport.OutboundTransfer t = transfer(msg, payment);
        List<String> tried = new ArrayList<>();
        boolean maybeSent = false;
        for (Ranked r : nodes) {
            Outcome o = r.transport().send(r.node(), t);
            tried.add(r.node().name() + "=" + o.kind());
            if (o.answered()) {
                healthy(r, o.latencyMs());
                return view(msg, record(msg, r, o, false));
            }
            failed(r, o.detail());
            if (o.kind() == Kind.TIMEOUT) {
                maybeSent = true;
                Answer a = queryAll(nodes, e2e);
                if (a != null) {
                    return view(msg, record(msg, a.node(), a.outcome(), false));
                }
            }
        }
        if (maybeSent) {
            ObjectNode pending = msg.deepCopy();
            pending.put("error", "PENDING: no final answer within the timeout (" + String.join(", ", tried)
                    + "); resolve with POST /v1/payment-rails/" + railId + "/query-status");
            return pending;
        }
        throw new ApiException(503, "rail_unreachable", "No access node of the rail is reachable ("
                + String.join(", ", tried) + ")");
    }

    private Answer queryAll(List<Ranked> nodes, String e2e) {
        for (Ranked r : nodes) {
            Outcome q = r.transport().query(r.node(), e2e);
            if (q.answered()) {
                healthy(r, q.latencyMs());
                return new Answer(r, q);
            }
            if (q.kind() == Kind.UNREACHABLE) {
                failed(r, q.detail());
            } else if (q.kind() != Kind.NOT_FOUND) {
                healthy(r, q.latencyMs()); // answered "in process": the node is fine
            }
        }
        return null;
    }

    /** Stores the rail's answer as an INBOUND pacs.002 (append-only); optionally applies it to the payment. */
    private ObjectNode record(ObjectNode msg, Ranked r, Outcome o, boolean applyToPayment) {
        String raw = o.raw() == null ? "{}" : o.raw();
        UUID id = Ids.newId();
        ObjectNode in = rows.object();
        in.put("id", id.toString());
        in.put("payment_rail_id", text(msg, "payment_rail_id"));
        in.put("direction", "INBOUND");
        in.put("message_type", "pacs.002");
        in.put("message_id", "A" + id.toString().replace("-", "").toUpperCase().substring(0, 31));
        in.put("end_to_end_id", text(msg, "end_to_end_id"));
        if (msg.hasNonNull("payment_id")) {
            in.put("payment_id", text(msg, "payment_id"));
        }
        in.put("status", "PARSED");
        in.put("payload", raw);
        in.put("payload_hash", BankCodes.sha256(raw));
        in.put("payload_uri", "db://rail_message/" + id);
        in.put("signature_valid", true);
        in.put("error", "RJCT".equals(o.status()) ? "RJCT " + nz(o.reasonCode(), "NARR") + ": " + nz(o.reason(), "") : null);
        // transactional outbox: the pacs.002 row and its event commit together (never one without the other)
        ObjectNode stored = tx.execute(s -> {
            ObjectNode row = rows.insert(MessageService.MESSAGE, in);
            events.publish("payments.rail_message.received", MessageService.MESSAGE, row); // the rail's pacs.002 answer
            return row;
        });
        if (applyToPayment && msg.hasNonNull("payment_id")) {
            ObjectNode body = rows.object();
            // a real-time gross settlement rail (Bre-B) settles on acceptance: ACCP is final
            body.put("status", "ACCP".equals(o.status()) ? "ACSC" : "RJCT");
            if (o.reasonCode() != null) {
                body.put("reason_code", o.reasonCode());
            }
            if (o.reason() != null) {
                body.put("reason", o.reason());
            }
            if (o.clearingRef() != null) {
                body.put("clearing_system_ref", o.clearingRef());
            }
            body.put("rail_message_id", id.toString());
            downstream.post(Downstream.PAYMENTS, "/v1/payments/" + text(msg, "payment_id") + "/status-report", body,
                    "rail-answer:" + id);
        }
        return stored;
    }

    /** Outbound message view with the outcome derived from its pacs.002 answer. */
    private ObjectNode view(ObjectNode outbound, ObjectNode answer) {
        ObjectNode v = outbound.deepCopy();
        JsonNode a = parse(text(answer, "payload"));
        String st = a == null ? null : text(a, "status");
        if ("RJCT".equals(st)) {
            v.put("status", "NACKED");
            v.put("error", "RJCT " + nz(text(a, "reason_code"), "NARR") + ": " + nz(text(a, "reason"), "Rejected by the rail"));
        } else {
            v.put("status", "ACKNOWLEDGED");
            v.putNull("error");
        }
        return v;
    }

    private Optional<ObjectNode> answerOf(UUID railId, String e2e) {
        if (e2e == null) {
            return Optional.empty();
        }
        return rows.queryOne("""
                select row_to_json(t)::text from rail_message t where payment_rail_id = ? and end_to_end_id = ?
                and direction = 'INBOUND' and message_type = 'pacs.002' order by created_at desc limit 1""", railId, e2e);
    }

    private Optional<ObjectNode> outboundOf(UUID railId, String e2e) {
        return rows.queryOne("""
                select row_to_json(t)::text from rail_message t where payment_rail_id = ? and end_to_end_id = ?
                and direction = 'OUTBOUND' order by created_at limit 1""", railId, e2e);
    }

    private RailTransport.OutboundTransfer transfer(ObjectNode msg, JsonNode p) {
        BigDecimal amount = p.hasNonNull("amount") ? new BigDecimal(p.get("amount").asString()) : BigDecimal.ZERO;
        return new RailTransport.OutboundTransfer(text(msg, "message_id"), text(msg, "end_to_end_id"), amount,
                text(p, "currency"), text(p, "debtor_name"), text(p, "debtor_account_number"), text(p, "creditor_name"),
                text(p, "creditor_account_number"), text(p, "creditor_account_id_type"),
                text(p, "creditor_agent_clearing_id"), text(p, "remittance_unstructured"), text(p, "purpose_code"));
    }

    // ================================================================== status query

    /** pacs.028: asks the rail for an outbound payment's status; a final answer is stored and applied to it. */
    public ObjectNode query(UUID railId, String e2e) {
        ObjectNode msg = outboundOf(railId, e2e).orElseThrow(() -> ApiException.notFound("rail_message", e2e));
        ObjectNode out = rows.object();
        out.put("end_to_end_id", e2e);
        out.put("payment_id", text(msg, "payment_id"));
        Optional<ObjectNode> prior = answerOf(railId, e2e);
        if (prior.isPresent()) {
            out.put("outcome", "already_final");
            out.put("status", text(view(msg, prior.get()), "status"));
            return out;
        }
        List<Ranked> nodes = nodes(railId);
        if (nodes.isEmpty()) {
            throw ApiException.unprocessable("rail_not_networked", "The rail has no network access node");
        }
        Answer a = queryAll(nodes, e2e);
        if (a == null) {
            boolean received = false;
            boolean reachable = false;
            for (Ranked r : nodes) {
                Outcome q = r.transport().query(r.node(), e2e);
                if (q.kind() == Kind.NOT_FOUND) {
                    reachable = true;
                } else if (q.kind() != Kind.UNREACHABLE) {
                    reachable = true;
                    received = true;
                }
            }
            if (reachable && !received) {
                // the rail never got it (every node answered "not found"): the payment's next submission resends it
                out.put("outcome", "not_received");
                out.put("status", "SENT");
                out.put("detail", "The rail has no record of this transfer; it was never delivered and can be resubmitted");
                return out;
            }
            out.put("outcome", "pending");
            out.put("status", "SENT");
            out.put("detail", "The rail has not answered yet (or no node is reachable)");
            return out;
        }
        record(msg, a.node(), a.outcome(), true);
        out.put("outcome", "applied");
        out.put("status", "ACCP".equals(a.outcome().status()) ? "ACKNOWLEDGED" : "NACKED");
        out.put("rail_status", a.outcome().status());
        if (a.outcome().reasonCode() != null) {
            out.put("reason_code", a.outcome().reasonCode());
        }
        out.put("via_node", a.node().node().name());
        return out;
    }

    /** Resolves every pending outbound message of the rail older than the given age (timeouts sweep). */
    public ObjectNode resolvePending(UUID railId, int olderThanSeconds) {
        List<ObjectNode> pending = rows.query("""
                select row_to_json(o)::text from rail_message o where o.payment_rail_id = ? and o.direction = 'OUTBOUND'
                and o.created_at < now() - make_interval(secs => ?) and o.end_to_end_id is not null
                and not exists (select 1 from rail_message i where i.payment_rail_id = o.payment_rail_id
                  and i.end_to_end_id = o.end_to_end_id and i.direction = 'INBOUND' and i.message_type = 'pacs.002')
                order by o.created_at limit 200""", railId, Math.max(0, olderThanSeconds));
        int applied = 0;
        int still = 0;
        int notReceived = 0;
        ArrayNode items = rows.json().createArrayNode();
        for (ObjectNode m : pending) {
            ObjectNode q = query(railId, text(m, "end_to_end_id"));
            if ("applied".equals(text(q, "outcome"))) {
                applied++;
            } else if ("not_received".equals(text(q, "outcome"))) {
                notReceived++;
            } else {
                still++;
            }
            items.add(q);
        }
        ObjectNode out = rows.object();
        out.put("examined", pending.size());
        out.put("applied", applied);
        out.put("still_pending", still);
        out.put("not_received", notReceived);
        out.set("items", items);
        return out;
    }

    // ================================================================== key directory

    public ObjectNode resolveAlias(UUID railId, String keyType, String keyValue) {
        return directory(railId, "RESOLVE", keyType, keyValue, null);
    }

    public ObjectNode registerAlias(UUID railId, String keyType, String keyValue, JsonNode body) {
        return directory(railId, "REGISTER", keyType, keyValue, body);
    }

    public ObjectNode deleteAlias(UUID railId, String keyType, String keyValue) {
        return directory(railId, "DELETE", keyType, keyValue, null);
    }

    private ObjectNode directory(UUID railId, String op, String keyType, String keyValue, JsonNode body) {
        if (keyType == null || keyType.isBlank() || keyValue == null || keyValue.isBlank()) {
            throw ApiException.unprocessable("key_required", "key_type and key_value are required");
        }
        List<Ranked> nodes = nodes(railId);
        if (nodes.isEmpty()) {
            throw ApiException.unprocessable("rail_not_networked", "The rail has no network access node with a directory");
        }
        List<String> tried = new ArrayList<>();
        for (Ranked r : nodes) {
            RailTransport.DirectoryResult d = r.transport().directory(r.node(), op, keyType.trim().toUpperCase(),
                    keyValue.trim(), body);
            if (d.status() == 0) {
                tried.add(r.node().name() + "=" + d.detail());
                failed(r, d.detail());
                continue;
            }
            healthy(r, r.latency());
            JsonNode b = d.body();
            String code = b == null ? null : text(b, "code");
            String detail = b == null ? null : text(b, "detail");
            switch (d.status()) {
                case 200, 201 -> {
                    ObjectNode res = b == null ? rows.object() : ((ObjectNode) b).deepCopy();
                    res.put("via_node", r.node().name());
                    return res;
                }
                case 404 -> throw new ApiException(404, "key_not_found", nz(detail, "The key is not registered at the rail directory"));
                case 409 -> throw ApiException.conflict(nz(code, "key_in_use").toLowerCase(), nz(detail, "The key is held by another participant"));
                case 403 -> throw new ApiException(403, "not_key_holder", nz(detail, "Only the holding participant can change this key"));
                default -> throw ApiException.unprocessable(nz(code, "key_invalid").toLowerCase(), nz(detail, "The directory refused the key"));
            }
        }
        throw new ApiException(503, "rail_unreachable", "No access node of the rail is reachable (" + String.join(", ", tried) + ")");
    }

    // ================================================================== helpers

    private JsonNode parse(String raw) {
        try {
            return raw == null || raw.isBlank() ? null : rows.json().readTree(raw);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String text(JsonNode n, String f) {
        if (n == null) {
            return null;
        }
        JsonNode v = n.path(f);
        return v.isMissingNode() || v.isNull() ? null : v.asString();
    }

    private static String nz(String v, String d) {
        return v == null || v.isBlank() ? d : v;
    }
}
