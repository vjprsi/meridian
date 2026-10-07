package io.meridian.routing.application;

import static io.meridian.routing.infrastructure.Rows.bool;
import static io.meridian.routing.infrastructure.Rows.str;
import static io.meridian.routing.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.routing.config.RoutingProperties;
import io.meridian.routing.domain.BankCodes;
import io.meridian.routing.domain.IsoMessages;
import io.meridian.routing.infrastructure.Downstream;
import io.meridian.routing.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Inbound rail messages pushed by a rail or sponsor bank (a [public] provider callback: no token, no tenant header).
 * The tenant is resolved from the referenced rail (SECURITY DEFINER lookup), the HMAC signature is verified with the
 * rail's callback secret, the raw message is stored append-only and then applied to payment-service:
 * pacs.002 -> status report, pacs.008 -> inbound credit, camt.056 -> inbound recall, pacs.004 -> inbound return.
 * Messages with a bad signature or that cannot be parsed are stored as FAILED (audit trail) and rejected; processing
 * errors after a valid parse are stored as FAILED and can be replayed. Redelivery of an already processed MsgId
 * returns the stored message without reprocessing.
 */
@Service
public class InboundService {
    private final Rows rows;
    private final Events events;
    private final Downstream downstream;
    private final SettlementService settlement;
    private final RoutingProperties props;
    private final TransactionTemplate tx;
    private final io.meridian.web.callback.CallbackVerifier callbacks;
    private final io.meridian.web.tenancy.AdvisoryLocks locks;

    public InboundService(Rows rows, Events events, Downstream downstream, SettlementService settlement,
                          RoutingProperties props, TransactionTemplate tx,
                          io.meridian.web.callback.CallbackVerifier callbacks,
                          io.meridian.web.tenancy.AdvisoryLocks locks) {
        this.callbacks = callbacks;
        this.locks = locks;
        this.rows = rows;
        this.events = events;
        this.downstream = downstream;
        this.settlement = settlement;
        this.props = props;
        this.tx = tx;
    }

    private record Outcome(String status, String error, UUID paymentId, ArrayNode results, boolean creditBatch,
                           String currency, LocalDate date, BigDecimal credits) {}

    /** Public callback entry point. */
    public ObjectNode receive(UUID railId, String declaredType, String payload, String signature) {
        UUID tenant = rows.scalar("select t from (select public_rail_tenant(?) as t) x where t is not null", UUID.class, railId)
                .orElseThrow(() -> ApiException.notFound("payment rail", railId));
        try {
            return TenantContext.callAs(tenant, "rail:" + railId, () -> receiveAsTenant(railId, declaredType, payload,
                    signature));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ObjectNode receiveAsTenant(UUID railId, String declaredType, String payload, String signature) {
        ObjectNode rail = rows.get(RailService.RAIL, railId);
        String secret = props.secretFor(str(rail, "code"));
        if (payload == null || payload.isBlank()) {
            throw ApiException.badRequest("payload_required", "payload is required");
        }
        // SEC-17: v2 signature over "<timestamp>.<nonce>.<rail id>.<payload>", fresh and single-use; legacy
        // payload-only signatures only while meridian.callbacks.accept-legacy-signatures is on
        try {
            if (secret == null) {
                throw new ApiException(401, "invalid_signature", "No callback secret is configured for this rail");
            }
            callbacks.verify("rail:" + railId, railId.toString(), payload.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    signature, io.meridian.web.callback.CallbackVerifier.Headers.current(),
                    b -> BankCodes.sign(secret, new String(b, java.nio.charset.StandardCharsets.UTF_8)));
        } catch (ApiException e) {
            if ("invalid_signature".equals(e.code()) || "stale_callback".equals(e.code())) {
                storeRejected(railId, IsoMessages.canonical(declaredType), "UNKNOWN", null, payload, "FAILED", false,
                        "invalid or missing X-Rail-Signature (" + e.code() + ")", null, null);
            }
            throw e;
        }
        IsoMessages.Parsed parsed;
        try {
            parsed = IsoMessages.parse(payload);
        } catch (IsoMessages.InvalidMessage e) {
            storeRejected(railId, IsoMessages.canonical(declaredType), "UNKNOWN", null, payload, "FAILED", true,
                    e.getMessage(), null, null);
            throw ApiException.unprocessable("invalid_message", e.getMessage());
        }
        if (!parsed.type().equals(IsoMessages.canonical(declaredType))) {
            throw ApiException.unprocessable("message_type_mismatch", "message_type " + declaredType
                    + " does not match the payload (" + parsed.type() + ")");
        }
        String msgId = parsed.messageId() == null ? "NOID-" + BankCodes.sha256(payload).substring(0, 32) : parsed.messageId();
        // SEC-28: concurrent deliveries of one message (several replicas) are serialised, so the duplicate check and
        // the processing cannot interleave and credit twice
        return locks.withLock("rail-in:" + railId + ":" + msgId, java.time.Duration.ofSeconds(25), () -> {
            Optional<ObjectNode> dup = rows.queryOne("""
                    select row_to_json(t)::text from rail_message t where payment_rail_id = ? and direction = 'INBOUND'
                    and message_id = ? and replay_of_id is null and status = 'PARSED' order by created_at limit 1""",
                    railId, msgId);
            if (dup.isPresent()) {
                return dup.get();
            }
            UUID id = Ids.newId();
            Outcome o = process(id, rail, parsed);
            return store(id, rail, parsed, msgId, payload, o, null);
        });
    }

    /** Re-processes a stored inbound message; downstream calls are keyed by the original message, so applied steps dedupe. */
    public ObjectNode replay(UUID messageId, String reason) {
        ObjectNode original = rows.get(MessageService.MESSAGE, messageId);
        if (!"INBOUND".equals(str(original, "direction"))) {
            throw ApiException.conflict("not_replayable", "Only inbound messages can be replayed");
        }
        if (!bool(original, "signature_valid")) {
            throw ApiException.conflict("not_replayable", "The message failed signature verification and cannot be replayed");
        }
        UUID root = uuid(original, "replay_of_id") != null ? uuid(original, "replay_of_id") : messageId;
        ObjectNode rail = rows.get(RailService.RAIL, uuid(original, "payment_rail_id"));
        IsoMessages.Parsed parsed;
        try {
            parsed = IsoMessages.parse(str(original, "payload"));
        } catch (IsoMessages.InvalidMessage e) {
            throw ApiException.unprocessable("invalid_message", e.getMessage());
        }
        Outcome o = process(root, rail, parsed);
        ObjectNode stored = store(Ids.newId(), rail, parsed, str(original, "message_id"), str(original, "payload"), o, root);
        ObjectNode out = rows.object();
        out.put("original_message_id", root.toString());
        out.put("replayed_message_id", str(stored, "id"));
        out.put("message_type", parsed.type());
        out.put("status", o.status());
        out.put("error", o.error());
        out.put("payment_id", o.paymentId() == null ? null : o.paymentId().toString());
        out.put("reason", reason);
        out.set("results", o.results());
        return out;
    }

    // ------------------------------------------------------------------ processing

    private Outcome process(UUID root, ObjectNode rail, IsoMessages.Parsed parsed) {
        ArrayNode results = rows.json().createArrayNode();
        UUID paymentId = null;
        BigDecimal credits = BigDecimal.ZERO;
        String currency = null;
        LocalDate date = null;
        String error = null;
        int i = 0;
        for (Map<String, String> t : parsed.transactions()) {
            ObjectNode r = results.addObject();
            String key = "rmsg:" + root + ":" + parsed.type() + ":" + i++;
            try {
                switch (parsed.type()) {
                    case "pacs.002" -> {
                        String status = t.get("status");
                        r.put("end_to_end_id", t.get("original_end_to_end_id"));
                        r.put("status", status);
                        if (status == null || !java.util.Set.of("ACCP", "ACSP", "ACSC", "ACWC", "RJCT").contains(status)) {
                            r.put("outcome", "ignored");
                            continue;
                        }
                        String e2e = t.get("original_end_to_end_id");
                        if (e2e == null) {
                            throw ApiException.unprocessable("missing_end_to_end_id", "status report without OrgnlEndToEndId");
                        }
                        JsonNode payment = downstream.paymentByEndToEndId(e2e).orElseThrow(() ->
                                ApiException.unprocessable("unknown_payment", "No payment with end_to_end_id " + e2e));
                        paymentId = UUID.fromString(payment.get("id").asString());
                        ObjectNode body = rows.object();
                        body.put("status", "ACWC".equals(status) ? "ACCP" : status);
                        body.put("reason_code", t.get("reason_code"));
                        body.put("reason", t.get("reason"));
                        body.put("clearing_system_ref", t.get("clearing_system_ref"));
                        body.put("rail_message_id", root.toString());
                        body.put("settlement_date", t.get("settlement_date"));
                        downstream.post(Downstream.PAYMENTS, "/v1/payments/" + paymentId + "/status-report", body, key);
                        r.put("payment_id", paymentId.toString());
                        r.put("outcome", "applied");
                    }
                    case "pacs.008" -> {
                        ObjectNode body = rows.object();
                        body.put("rail_code", str(rail, "code"));
                        body.put("rail_message_id", root.toString());
                        for (String f : new String[] {"end_to_end_id", "uetr", "transaction_id", "amount", "currency",
                                "debtor_name", "debtor_account_number", "debtor_agent_bic", "creditor_name",
                                "creditor_account_number", "remittance_unstructured", "settlement_date"}) {
                            body.put(f, t.get(f));
                        }
                        if (t.get("end_to_end_id") == null) {
                            body.put("end_to_end_id", t.get("transaction_id") != null ? t.get("transaction_id") : "NOTPROVIDED");
                        }
                        JsonNode p = downstream.post(Downstream.PAYMENTS, "/v1/payments/inbound", body, key);
                        paymentId = p.hasNonNull("id") ? UUID.fromString(p.get("id").asString()) : null;
                        r.put("end_to_end_id", body.get("end_to_end_id").asString());
                        r.put("payment_id", paymentId == null ? null : paymentId.toString());
                        r.put("outcome", "credited");
                        if (t.get("amount") != null) {
                            credits = credits.add(new BigDecimal(t.get("amount")));
                            currency = t.get("currency");
                            date = t.get("settlement_date") == null ? null : LocalDate.parse(t.get("settlement_date"));
                        }
                    }
                    case "camt.056" -> {
                        ObjectNode body = rows.object();
                        body.put("original_end_to_end_id", t.get("original_end_to_end_id"));
                        body.put("reason_code", t.get("reason_code") == null ? "CUST" : t.get("reason_code"));
                        body.put("reason", t.get("reason"));
                        body.put("requested_by", t.get("requested_by"));
                        JsonNode rc = downstream.post(Downstream.PAYMENTS, "/v1/payment-recalls/inbound", body, key);
                        if (rc.hasNonNull("payment_id")) {
                            paymentId = UUID.fromString(rc.get("payment_id").asString());
                        }
                        r.put("end_to_end_id", t.get("original_end_to_end_id"));
                        r.put("recall_id", rc.path("id").asString(null));
                        r.put("outcome", "recall_registered");
                    }
                    case "pacs.004" -> {
                        ObjectNode body = rows.object();
                        body.put("original_end_to_end_id", t.get("original_end_to_end_id"));
                        body.put("reason_code", t.get("reason_code") == null ? "NARR" : t.get("reason_code"));
                        body.put("amount", t.get("amount"));
                        body.put("return_id", t.get("return_id"));
                        body.put("reason", t.get("reason"));
                        body.put("rail_message_id", root.toString());
                        JsonNode rt = downstream.post(Downstream.PAYMENTS, "/v1/payment-returns/inbound", body, key);
                        if (rt.hasNonNull("payment_id")) {
                            paymentId = UUID.fromString(rt.get("payment_id").asString());
                        }
                        r.put("end_to_end_id", t.get("original_end_to_end_id"));
                        r.put("return_id", rt.path("id").asString(null));
                        r.put("outcome", "return_booked");
                    }
                    default -> throw ApiException.unprocessable("unsupported_message", parsed.type());
                }
            } catch (ApiException e) {
                r.put("outcome", "failed");
                r.put("error", e.code() + ": " + e.getMessage());
                error = error == null ? e.code() + ": " + e.getMessage() : error;
            } catch (RuntimeException e) {
                r.put("outcome", "failed");
                r.put("error", e.getMessage());
                error = error == null ? "processing_error: " + e.getMessage() : error;
            }
        }
        if (parsed.transactions().isEmpty()) {
            error = "message contains no transactions";
        }
        boolean creditBatch = error == null && "pacs.008".equals(parsed.type()) && !bool(rail, "is_instant")
                && credits.signum() > 0 && currency != null;
        return new Outcome(error == null ? "PARSED" : "FAILED", error, paymentId, results, creditBatch, currency, date,
                credits);
    }

    private ObjectNode store(UUID id, ObjectNode rail, IsoMessages.Parsed parsed, String msgId, String payload, Outcome o,
                             UUID replayOf) {
        return tx.execute(s -> {
            UUID batch = null;
            if (o.creditBatch() && !alreadyCredited(replayOf == null ? id : replayOf)) {
                LocalDate d = o.date() != null ? o.date()
                        : rows.today();
                batch = settlement.add(rail, o.currency(), d, o.credits(), false);
            }
            String e2e = parsed.transactions().isEmpty() ? null : first(parsed.first(), "end_to_end_id",
                    "original_end_to_end_id");
            ObjectNode m = row(id, uuid(rail, "id"), parsed.type(), msgId, e2e, payload, o.status(), true, o.error(),
                    o.paymentId(), batch, replayOf);
            ObjectNode stored = rows.insert(MessageService.MESSAGE, m);
            events.publish("payments.rail_message.received", MessageService.MESSAGE, stored);
            return stored;
        });
    }

    /** Stores a rejected message (bad signature / unparseable) in its own transaction, for the audit trail. */
    private void storeRejected(UUID railId, String type, String msgId, String e2e, String payload, String status,
                       boolean signatureValid, String error, UUID paymentId, UUID batch) {
        UUID id = Ids.newId();
        tx.executeWithoutResult(s -> rows.insert(MessageService.MESSAGE, row(id, railId,
                type == null ? "unknown" : type, msgId + "-" + id.toString().substring(0, 8), e2e, payload, status,
                signatureValid, error, paymentId, batch, null)));
    }

    private boolean alreadyCredited(UUID root) {
        return rows.scalar("""
                select count(*) from rail_message where (id = ? or replay_of_id = ?) and status = 'PARSED'
                and rail_settlement_batch_id is not null""", Long.class, root, root).orElse(0L) > 0;
    }

    private ObjectNode row(UUID id, UUID railId, String type, String msgId, String e2e, String payload, String status,
                           boolean signatureValid, String error, UUID paymentId, UUID batch, UUID replayOf) {
        ObjectNode m = rows.object();
        m.put("id", id.toString());
        m.put("payment_rail_id", railId.toString());
        m.put("direction", "INBOUND");
        m.put("message_type", type.length() > 32 ? type.substring(0, 32) : type);
        m.put("message_id", msgId.length() > 64 ? msgId.substring(0, 64) : msgId);
        m.put("end_to_end_id", e2e == null || e2e.length() > 35 ? null : e2e);
        m.put("payment_id", paymentId == null ? null : paymentId.toString());
        m.put("rail_settlement_batch_id", batch == null ? null : batch.toString());
        m.put("replay_of_id", replayOf == null ? null : replayOf.toString());
        m.put("status", status);
        m.put("payload", payload);
        m.put("payload_hash", BankCodes.sha256(payload));
        m.put("payload_uri", "db://rail_message/" + id);
        m.put("signature_valid", signatureValid);
        m.put("error", error);
        return m;
    }

    private static String first(Map<String, String> m, String a, String b) {
        return m.get(a) != null ? m.get(a) : m.get(b);
    }
}
