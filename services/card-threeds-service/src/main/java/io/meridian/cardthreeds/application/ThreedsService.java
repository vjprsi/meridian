package io.meridian.cardthreeds.application;

import io.meridian.cardthreeds.config.CardsProperties;
import io.meridian.cardthreeds.domain.Crypto;
import io.meridian.cardthreeds.domain.RiskScorer;
import io.meridian.cardthreeds.infrastructure.Downstream;
import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * ACS use cases: AReq handling with risk-based authentication, OTP / app / biometric challenges and the results
 * request of the directory server. The PAN is used only to resolve the card through the vault and is never stored.
 * The CAVV is stored as a secret column and returned only to the directory server. Times come from the DB clock.
 */
@Service
public class ThreedsService {
    static final String TABLE = "threeds_authentication";
    static final Set<String> OTP_CHALLENGES = Set.of("OTP_SMS", "OTP_EMAIL");
    static final Set<String> APP_CHALLENGES = Set.of("APP_PUSH", "BIOMETRIC", "DECOUPLED");

    private final JdbcClient jdbc;
    private final CrudEngine crud;
    private final JsonMapper json;
    private final TransactionTemplate tx;
    private final Events events;
    private final Downstream downstream;
    private final CardsProperties.Threeds cfg;

    public ThreedsService(JdbcClient jdbc, CrudEngine crud, JsonMapper json, TransactionTemplate tx, Events events,
                          Downstream downstream, CardsProperties props) {
        this.jdbc = jdbc;
        this.crud = crud;
        this.json = json;
        this.tx = tx;
        this.events = events;
        this.downstream = downstream;
        this.cfg = props.threeds();
    }

    /** AReq from the directory server; a replay of the same ds_transaction_id returns the original answer. */
    public ObjectNode authenticate(JsonNode req) {
        String dsTx = req.get("ds_transaction_id").asString();
        Optional<ObjectNode> existing = byDsTransaction(dsTx);
        if (existing.isPresent()) {
            return withCavv(existing.get());
        }
        UUID cardId = downstream.cardIdForPan(req.get("pan").asString(), "3ds-lookup:" + sha(dsTx));
        JsonNode ctx = downstream.authContext(cardId);
        JsonNode card = ctx.path("card");
        String scheme = ctx.path("program").path("scheme").asString(null);
        BigDecimal amount = req.hasNonNull("amount") ? new BigDecimal(req.get("amount").asString()) : null;
        String currency = req.path("currency").asString(null);
        String merchant = req.path("merchant_name").asString(null);
        RiskScorer.Input input = new RiskScorer.Input(amount, req.path("mcc").asString(null),
                req.path("merchant_country").asString(null), req.path("device_channel").asString(null),
                card.path("status").asString(null));
        RiskScorer.Assessment a = RiskScorer.assess(input, cfg.threeRiMaxAmount());
        String otp = Crypto.newOtp(6);
        try {
            return tx.execute(s -> {
                // serialise concurrent deliveries of the same AReq, then re-check for a winner
                jdbc.sql("select pg_advisory_xact_lock(hashtextextended(?, 0))").param("3ds:" + dsTx)
                        .query(Object.class).list();
                Optional<ObjectNode> raced = byDsTransaction(dsTx);
                if (raced.isPresent()) {
                    return withCavv(raced.get());
                }
                OffsetDateTime now = jdbc.sql("select now()").query(OffsetDateTime.class).single();
                UUID id = Ids.newId();
                String acsTx = UUID.randomUUID().toString();
                ObjectNode v = json.createObjectNode();
                v.put("id", id.toString());
                v.put("card_id", cardId.toString());
                text(v, "customer_id", card.path("customer_id").asString(null));
                text(v, "card_last4", card.path("last4").asString(null));
                v.put("acs_transaction_id", acsTx);
                v.put("ds_transaction_id", dsTx);
                for (String f : List.of("threeds_server_transaction_id", "protocol_version", "merchant_name",
                        "merchant_country", "mcc", "currency", "device_channel")) {
                    text(v, f, req.path(f).asString(null));
                }
                if (amount != null) {
                    v.put("amount", amount.toPlainString());
                }
                v.put("risk_score", a.score());
                v.set("risk_reasons", json.valueToTree(a.reasons()));
                String cavv = null;
                switch (a.outcome()) {
                    case FRICTIONLESS -> {
                        cavv = Crypto.cavv(cfg.cavvKey(), acsTx, cardId, amount, currency);
                        v.put("challenge_type", "FRICTIONLESS");
                        v.put("result", "AUTHENTICATED");
                        text(v, "eci", RiskScorer.eci(scheme, "AUTHENTICATED"));
                        v.put("authentication_value", cavv);
                        v.put("completed_at", now.toString());
                    }
                    case CHALLENGE -> {
                        String type = RiskScorer.challengeType(req.path("preferred_challenge").asString(null));
                        v.put("challenge_type", type);
                        v.put("result", "CHALLENGE_PENDING");
                        v.put("challenge_expires_at", now.plusMinutes(cfg.challengeTtlMinutes()).toString());
                        if (OTP_CHALLENGES.contains(type)) {
                            v.put("otp_hash", Crypto.otpHash(cfg.otpKey(), id.toString(), otp));
                        }
                    }
                    case REJECT -> {
                        v.put("result", "REJECTED");
                        v.put("decline_reason", "Risk score " + a.score() + ": " + String.join(", ", a.reasons()));
                        v.put("completed_at", now.toString());
                    }
                }
                ObjectNode row = (ObjectNode) json.readTree(crud.insert(TABLE, v));
                if (a.outcome() == RiskScorer.Outcome.CHALLENGE) {
                    String type = row.get("challenge_type").asString();
                    if (OTP_CHALLENGES.contains(type)) {
                        String customer = card.path("customer_id").asString(null);
                        if (customer == null) {
                            throw ApiException.unprocessable("cardholder_unknown", "The card has no cardholder to challenge");
                        }
                        downstream.sendOtp(UUID.fromString(customer), "OTP_EMAIL".equals(type) ? "EMAIL" : "SMS", otp,
                                merchant, amount == null ? null : amount.toPlainString(), currency, "3ds-otp:" + id);
                    }
                    events.publish("cards.threeds_authentication.challenge_required", row);
                    return row;
                }
                events.publish("cards.threeds_authentication.completed", row);
                if (cavv != null) {
                    row.put("cavv", cavv);
                }
                return row;
            });
        } catch (DuplicateKeyException e) {
            return byDsTransaction(dsTx).map(this::withCavv).orElseThrow(() -> e);
        }
    }

    /** OTP challenge answer; wrong and expired answers are committed before the error is returned. */
    public ObjectNode verifyOtp(UUID id, String otp) {
        record Outcome(ObjectNode row, String error, String message) {}
        Outcome o = tx.execute(s -> {
            ObjectNode t = pending(id);
            if (!OTP_CHALLENGES.contains(t.path("challenge_type").asString(""))) {
                throw ApiException.conflict("invalid_transition", "The challenge is not an OTP challenge");
            }
            if (expired(id)) {
                fail(id, "Challenge expired");
                return new Outcome(null, "challenge_expired", "The challenge has expired; the authentication failed");
            }
            if (Crypto.otpMatches(cfg.otpKey(), id.toString(), otp, t.path("otp_hash").asString(null))) {
                return new Outcome(authenticated(t, "attempts = attempts + 1, "), null, null);
            }
            int attempts = t.path("attempts").asInt() + 1;
            if (attempts >= cfg.maxOtpAttempts()) {
                fail(id, "Too many wrong one-time passwords");
                return new Outcome(null, "otp_attempts_exhausted", "Too many wrong codes; the authentication failed");
            }
            update(id, "attempts = attempts + 1");
            return new Outcome(null, "invalid_otp", "The one-time password is not valid");
        });
        if (o.error() != null) {
            throw ApiException.unprocessable(o.error(), o.message());
        }
        return o.row();
    }

    /** Cardholder approval of an app-push, biometric or decoupled challenge. */
    public ObjectNode approve(UUID id, String biometricAssertion) {
        record Outcome(ObjectNode row, boolean expired) {}
        Outcome o = tx.execute(s -> {
            ObjectNode t = pending(id);
            String type = t.path("challenge_type").asString("");
            if (!APP_CHALLENGES.contains(type)) {
                throw ApiException.conflict("invalid_transition", "A " + type + " challenge is answered with an OTP");
            }
            if ("BIOMETRIC".equals(type) && (biometricAssertion == null || biometricAssertion.isBlank())) {
                throw ApiException.unprocessable("biometric_assertion_required", "A biometric assertion is required");
            }
            if (expired(id)) {
                fail(id, "Challenge expired");
                return new Outcome(null, true);
            }
            return new Outcome(authenticated(t, ""), false);
        });
        if (o.expired()) {
            throw ApiException.unprocessable("challenge_expired", "The challenge has expired; the authentication failed");
        }
        return o.row();
    }

    /** Cardholder declines the challenge: the authentication fails. */
    public ObjectNode decline(UUID id, String reason) {
        return tx.execute(s -> {
            pending(id);
            return fail(id, reason == null || reason.isBlank() ? "Declined by the cardholder" : reason);
        });
    }

    /** Results request of the directory server: the row with the CAVV when authenticated. */
    public ObjectNode result(UUID id, String dsTransactionId) {
        ObjectNode row = jdbc.sql("select row_to_json(t)::text from threeds_authentication t where id = ?").param(id)
                .query(String.class).optional().map(r -> (ObjectNode) json.readTree(r))
                .filter(r -> dsTransactionId.equals(r.path("ds_transaction_id").asString(null)))
                .orElseThrow(() -> ApiException.notFound("threeds authentication", id));
        return withCavv(row);
    }

    // ------------------------------------------------------------------ helpers

    private ObjectNode authenticated(ObjectNode t, String extraSet) {
        UUID id = UUID.fromString(t.get("id").asString());
        UUID cardId = UUID.fromString(t.get("card_id").asString());
        BigDecimal amount = t.hasNonNull("amount") ? new BigDecimal(t.get("amount").asString()) : null;
        String cavv = Crypto.cavv(cfg.cavvKey(), t.get("acs_transaction_id").asString(), cardId, amount,
                t.path("currency").asString(null));
        String scheme = schemeOf(t);
        ObjectNode row = (ObjectNode) json.readTree(jdbc.sql("update threeds_authentication t set " + extraSet
                        + "result = 'AUTHENTICATED', eci = ?, authentication_value = ?, otp_hash = null, completed_at = now(), "
                        + "updated_by = ? where id = ? returning row_to_json(t)::text")
                .params(RiskScorer.eci(scheme, "AUTHENTICATED"), cavv, TenantContext.principal(), id)
                .query(String.class).single());
        events.publish("cards.threeds_authentication.completed", row);
        return row;
    }

    private ObjectNode fail(UUID id, String reason) {
        ObjectNode row = (ObjectNode) json.readTree(jdbc.sql("update threeds_authentication t set result = 'FAILED', "
                        + "attempts = attempts + 1, decline_reason = ?, otp_hash = null, completed_at = now(), updated_by = ? "
                        + "where id = ? returning row_to_json(t)::text")
                .params(reason.length() > 255 ? reason.substring(0, 255) : reason, TenantContext.principal(), id)
                .query(String.class).single());
        events.publish("cards.threeds_authentication.completed", row);
        return row;
    }

    private void update(UUID id, String set) {
        jdbc.sql("update threeds_authentication set " + set + ", updated_by = ? where id = ?")
                .params(TenantContext.principal(), id).update();
    }

    /** The authentication, locked, which must still await the cardholder. */
    private ObjectNode pending(UUID id) {
        ObjectNode t = jdbc.sql("select row_to_json(t)::text from threeds_authentication t where id = ? for update")
                .param(id).query(String.class).optional().map(r -> (ObjectNode) json.readTree(r))
                .orElseThrow(() -> ApiException.notFound("threeds authentication", id));
        String result = t.get("result").asString();
        if (!"CHALLENGE_PENDING".equals(result)) {
            throw ApiException.conflict("invalid_transition", "Authentication is " + result + "; no challenge is pending");
        }
        return t;
    }

    private boolean expired(UUID id) {
        return jdbc.sql("select challenge_expires_at is not null and challenge_expires_at < now() "
                + "from threeds_authentication where id = ?").param(id).query(Boolean.class).single();
    }

    /** Card scheme (drives the ECI value) from card-service; null (Visa-style ECI) when it cannot be read. */
    private String schemeOf(ObjectNode t) {
        try {
            return downstream.authContext(UUID.fromString(t.get("card_id").asString())).path("program").path("scheme")
                    .asString(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private ObjectNode withCavv(ObjectNode row) {
        String result = row.path("result").asString("");
        if (("AUTHENTICATED".equals(result) || "ATTEMPTED".equals(result)) && row.hasNonNull("authentication_value")) {
            row.put("cavv", row.get("authentication_value").asString());
        }
        return row;
    }

    private Optional<ObjectNode> byDsTransaction(String dsTx) {
        return jdbc.sql("select row_to_json(t)::text from threeds_authentication t where ds_transaction_id = ? "
                        + "order by id limit 1").param(dsTx)
                .query(String.class).optional().map(r -> (ObjectNode) json.readTree(r));
    }

    private static void text(ObjectNode n, String f, String v) {
        if (v != null && !v.isBlank()) {
            n.put(f, v);
        }
    }

    private static String sha(String s) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
