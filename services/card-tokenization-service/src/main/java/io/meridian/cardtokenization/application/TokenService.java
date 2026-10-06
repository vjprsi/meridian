package io.meridian.cardtokenization.application;

import io.meridian.cardtokenization.config.CardsProperties;
import io.meridian.cardtokenization.domain.Crypto;
import io.meridian.cardtokenization.domain.ProvisioningDecision;
import io.meridian.cardtokenization.domain.ProvisioningDecision.Assessment;
import io.meridian.cardtokenization.domain.TokenLifecycle;
import io.meridian.cardtokenization.infrastructure.Downstream;
import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.web.crud.CrudEngine;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Network token use cases: push provisioning from the issuer app, provisioning requests and lifecycle notifications
 * from the scheme token services, OTP step-up, issuer lifecycle actions and card status propagation. Every status
 * change records a card_token_event and publishes its domain event in the same transaction. Timestamps come from the
 * database clock.
 */
@Service
public class TokenService {
    private static final String TABLE = "card_token";
    private static final String EVENTS = "card_token_event";

    private final JdbcClient jdbc;
    private final CrudEngine crud;
    private final JsonMapper json;
    private final TransactionTemplate tx;
    private final Events events;
    private final Downstream downstream;
    private final CardsProperties.Tokens cfg;

    public TokenService(JdbcClient jdbc, CrudEngine crud, JsonMapper json, TransactionTemplate tx, Events events,
                        Downstream downstream, CardsProperties props) {
        this.jdbc = jdbc;
        this.crud = crud;
        this.json = json;
        this.tx = tx;
        this.events = events;
        this.downstream = downstream;
        this.cfg = props.tokens();
    }

    // ------------------------------------------------------------------ provisioning

    /** Issuer-app push provisioning: the cardholder is authenticated in the app, so an active card is green-path. */
    public ObjectNode pushProvision(UUID cardId, String requestor, String requestorId, JsonNode deviceInfo) {
        JsonNode card = downstream.card(cardId);
        String cardStatus = card.path("status").asString();
        if (!"ACTIVE".equals(cardStatus)) {
            throw ApiException.unprocessable("card_not_active", "Card is " + cardStatus + "; only active cards can be provisioned");
        }
        return tx.execute(s -> {
            lockCard(cardId);
            if (liveTokens(cardId) >= cfg.maxPerCard()) {
                throw ApiException.unprocessable("token_limit_reached",
                        "The card already has " + cfg.maxPerCard() + " live tokens");
            }
            OffsetDateTime now = dbNow();
            UUID id = Ids.newId();
            ObjectNode v = json.createObjectNode();
            v.put("id", id.toString());
            v.put("card_id", cardId.toString());
            putIfText(v, "customer_id", card.path("customer_id").asString(null));
            v.put("token_requestor", requestor);
            putIfText(v, "token_requestor_id", requestorId);
            v.put("network_token_ref", newTokenRef());
            v.put("dpan_last4", Crypto.randomDigits(4));
            v.put("dpan_expiry_month", card.path("expiry_month").asInt());
            v.put("dpan_expiry_year", card.path("expiry_year").asInt());
            v.put("status", "ACTIVE");
            v.put("status_reason", "PUSH_PROVISIONED");
            v.put("provisioning_decision", "GREEN");
            device(v, deviceInfo);
            v.put("activated_at", now.toString());
            ObjectNode row = insert(v);
            ObjectNode payload = json.createObjectNode();
            payload.put("channel", "PUSH_PROVISIONING");
            payload.put("token_requestor", requestor);
            event(id, "PROVISIONING_REQUESTED", "ISSUER", null, payload);
            event(id, "PROVISIONING_APPROVED", "ISSUER", "LOW_RISK", null);
            event(id, "ACTIVATED", "ISSUER", null, null);
            events.publish("cards.card_token.provisioned", row);
            ObjectNode opaque = json.createObjectNode();
            opaque.put("card_id", cardId.toString());
            opaque.put("network_token_ref", row.get("network_token_ref").asString());
            opaque.put("nonce", Crypto.randomHex(16));
            ObjectNode result = row.deepCopy();
            result.put("provisioning_payload", Crypto.seal(cfg.payloadKey(), json.writeValueAsString(opaque)));
            return result;
        });
    }

    /** Provisioning request of the scheme token service (manual entry or card on file); replay-safe per token ref. */
    public ObjectNode networkProvision(JsonNode req) {
        String ref = req.path("network_token_ref").asString();
        Optional<ObjectNode> existing = byRef(ref);
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID cardId = downstream.cardIdForPan(req.path("pan").asString(), "token-lookup:" + Crypto.sha256Hex(ref));
        JsonNode card = downstream.card(cardId);
        boolean expiryMatches = card.path("expiry_month").asInt(-1) == req.path("expiry_month").asInt(-2)
                && card.path("expiry_year").asInt(-1) == req.path("expiry_year").asInt(-2);
        Integer wallet = intOrNull(req, "wallet_account_score");
        Integer device = intOrNull(req, "device_score");
        String requestor = req.path("token_requestor").asString();
        String otp = Crypto.randomDigits(6);
        try {
            return tx.execute(s -> {
                lockCard(cardId);
                Assessment a = ProvisioningDecision.assess(card.path("status").asString(), expiryMatches, wallet, device,
                        liveTokens(cardId), cfg.maxPerCard());
                OffsetDateTime now = dbNow();
                UUID id = Ids.newId();
                ObjectNode v = json.createObjectNode();
                v.put("id", id.toString());
                v.put("card_id", cardId.toString());
                putIfText(v, "customer_id", card.path("customer_id").asString(null));
                v.put("token_requestor", requestor);
                for (String f : List.of("token_requestor_id", "network_token_ref", "dpan_last4", "device_type",
                        "device_name", "device_id_hash", "merchant_name")) {
                    putIfText(v, f, req.path(f).asString(null));
                }
                v.put("dpan_expiry_month", card.path("expiry_month").asInt());
                v.put("dpan_expiry_year", card.path("expiry_year").asInt());
                if (wallet != null) {
                    v.put("wallet_account_score", wallet);
                }
                if (device != null) {
                    v.put("device_score", device);
                }
                v.put("provisioning_decision", a.path().name());
                v.put("status_reason", a.reason());
                switch (a.path()) {
                    case GREEN -> {
                        v.put("status", "ACTIVE");
                        v.put("activated_at", now.toString());
                    }
                    case YELLOW -> {
                        v.put("status", "PENDING_VERIFICATION");
                        v.put("step_up_method", "OTP_SMS");
                        v.put("otp_hash", Crypto.otpHash(cfg.otpKey(), id.toString(), otp));
                        v.put("otp_expires_at", now.plusMinutes(cfg.otpTtlMinutes()).toString());
                    }
                    case RED -> v.put("status", "DECLINED");
                }
                ObjectNode row = insert(v);
                event(id, "PROVISIONING_REQUESTED", "SCHEME", null, safePayload(req));
                switch (a.path()) {
                    case GREEN -> {
                        event(id, "PROVISIONING_APPROVED", "ISSUER", a.reason(), null);
                        event(id, "ACTIVATED", "ISSUER", null, null);
                        events.publish("cards.card_token.provisioned", row);
                    }
                    case YELLOW -> {
                        String customer = card.path("customer_id").asString(null);
                        if (customer == null) {
                            throw ApiException.unprocessable("cardholder_unknown", "The card has no cardholder to send a step-up OTP to");
                        }
                        downstream.sendStepUpOtp(UUID.fromString(customer), id, otp, requestor, "token-otp:" + id);
                        event(id, "STEP_UP_SENT", "ISSUER", "OTP_SMS", null);
                        events.publish("cards.card_token.step_up_required", row);
                    }
                    case RED -> {
                        event(id, "PROVISIONING_DECLINED", "ISSUER", a.reason(), null);
                        events.publish("cards.card_token.declined", row);
                    }
                }
                return row;
            });
        } catch (DuplicateKeyException e) {
            // a concurrent delivery of the same request won the race
            return byRef(ref).orElseThrow(() -> e);
        }
    }

    /** OTP step-up of a yellow-path provisioning. Failed attempts are committed before the error is returned. */
    public ObjectNode verifyStepUp(UUID id, String otp) {
        record Outcome(ObjectNode row, String error, String message) {}
        Outcome o = tx.execute(s -> {
            ObjectNode t = forUpdate(id);
            String status = t.get("status").asString();
            if (!"PENDING_VERIFICATION".equals(status)) {
                throw ApiException.conflict("invalid_transition", "Token is " + status + "; no step-up is pending");
            }
            boolean expired = jdbc.sql("select otp_expires_at is null or otp_expires_at < now() from card_token where id = ?")
                    .param(id).query(Boolean.class).single();
            if (expired) {
                return new Outcome(null, "otp_expired", "The step-up code has expired; request provisioning again");
            }
            if (Crypto.otpMatches(cfg.otpKey(), id.toString(), otp, t.path("otp_hash").asString(null))) {
                ObjectNode row = update(id, "status = 'ACTIVE', status_reason = 'STEP_UP_VERIFIED', activated_at = now(), "
                        + "otp_hash = null, otp_expires_at = null");
                event(id, "STEP_UP_VERIFIED", "ISSUER", null, null);
                event(id, "ACTIVATED", "ISSUER", null, null);
                events.publish("cards.card_token.provisioned", row);
                return new Outcome(row, null, null);
            }
            int attempts = t.path("step_up_attempts").asInt() + 1;
            event(id, "STEP_UP_FAILED", "ISSUER", "attempt " + attempts, null);
            if (attempts >= cfg.maxStepUpAttempts()) {
                ObjectNode row = update(id, "status = 'DECLINED', status_reason = 'STEP_UP_ATTEMPTS_EXCEEDED', "
                        + "step_up_attempts = step_up_attempts + 1, otp_hash = null, otp_expires_at = null");
                event(id, "PROVISIONING_DECLINED", "ISSUER", "STEP_UP_ATTEMPTS_EXCEEDED", null);
                events.publish("cards.card_token.declined", row);
                return new Outcome(null, "step_up_attempts_exceeded", "Too many wrong codes; the provisioning was declined");
            }
            update(id, "step_up_attempts = step_up_attempts + 1");
            return new Outcome(null, "invalid_otp", "The step-up code is not valid");
        });
        if (o.error() != null) {
            throw ApiException.unprocessable(o.error(), o.message());
        }
        return o.row();
    }

    // ------------------------------------------------------------------ lifecycle

    /** Lifecycle notification from the token service; a notification for the current status is acknowledged as is. */
    public ObjectNode networkEvent(String ref, String eventType, String reason) {
        String target;
        try {
            target = TokenLifecycle.targetForNetworkEvent(eventType);
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("unsupported_event", eventType + " is not a token lifecycle notification");
        }
        ObjectNode current = byRef(ref).orElseThrow(() -> ApiException.notFound("card token", ref));
        UUID id = UUID.fromString(current.get("id").asString());
        return tx.execute(s -> {
            ObjectNode t = forUpdate(id);
            String from = t.get("status").asString();
            if (target == null) {
                ObjectNode payload = json.createObjectNode();
                payload.put("event_type", eventType);
                event(id, eventType, "SCHEME", reason, payload);
                return t;
            }
            if (from.equals(target)) {
                return t;
            }
            return transition(t, target, reason, "SCHEME");
        });
    }

    /** Issuer action (suspend, resume, deactivate). */
    public ObjectNode issuerTransition(UUID id, String target, String reason) {
        return tx.execute(s -> transition(forUpdate(id), target, reason, "ISSUER"));
    }

    /** Applies a card status change to every token of the card; returns the number of tokens changed. */
    public int cardStatusSync(UUID cardId, String cardStatus) {
        String target = TokenLifecycle.targetForCardStatus(cardStatus);
        if (target == null) {
            return 0;
        }
        return tx.execute(s -> {
            List<String> rows = jdbc.sql("select row_to_json(t)::text from card_token t where card_id = ? "
                    + "and status in ('ACTIVE', 'SUSPENDED', 'PENDING_VERIFICATION', 'REQUESTED') order by id for update")
                    .param(cardId).query(String.class).list();
            int updated = 0;
            for (String r : rows) {
                ObjectNode t = (ObjectNode) json.readTree(r);
                String from = t.get("status").asString();
                if (from.equals(target) || !TokenLifecycle.allowed(from, target)) {
                    continue;
                }
                // resume only tokens the card freeze suspended, never ones the cardholder or scheme suspended
                if ("ACTIVE".equals(target) && !"CARD_FROZEN".equals(t.path("status_reason").asString(null))) {
                    continue;
                }
                String reason = switch (target) {
                    case "SUSPENDED" -> "CARD_FROZEN";
                    case "ACTIVE" -> "CARD_UNFROZEN";
                    default -> "CARD_" + cardStatus;
                };
                transition(t, target, reason, "ISSUER");
                updated++;
            }
            return updated;
        });
    }

    private ObjectNode transition(ObjectNode t, String target, String reason, String source) {
        UUID id = UUID.fromString(t.get("id").asString());
        String from = t.get("status").asString();
        if (!TokenLifecycle.allowed(from, target)) {
            throw ApiException.conflict("invalid_transition", "Token cannot go from " + from + " to " + target);
        }
        String extra = switch (target) {
            case "ACTIVE" -> "PENDING_VERIFICATION".equals(from) || "REQUESTED".equals(from)
                    ? ", activated_at = now(), otp_hash = null, otp_expires_at = null" : "";
            case "SUSPENDED" -> ", suspended_at = now()";
            case "DEACTIVATED" -> ", deactivated_at = now(), otp_hash = null, otp_expires_at = null";
            default -> "";
        };
        ObjectNode row = jdbc.sql("update card_token t set status = ?, status_reason = ?, updated_by = ?" + extra
                        + " where id = ? returning row_to_json(t)::text")
                .params(target, truncate(reason), io.meridian.common.tenancy.TenantContext.principal(), id)
                .query(String.class).optional().map(r -> (ObjectNode) json.readTree(r))
                .orElseThrow(() -> ApiException.notFound("card token", id));
        event(id, TokenLifecycle.eventFor(from, target), source, reason, null);
        if ("ACTIVE".equals(target) && !"SUSPENDED".equals(from)) {
            events.publish("cards.card_token.provisioned", row);
        } else {
            events.publish("cards.card_token.status_changed", row);
        }
        return row;
    }

    // ------------------------------------------------------------------ persistence helpers

    private ObjectNode insert(ObjectNode values) {
        return (ObjectNode) json.readTree(crud.insert(TABLE, values));
    }

    private void event(UUID tokenId, String type, String source, String reason, JsonNode payload) {
        ObjectNode e = json.createObjectNode();
        e.put("card_token_id", tokenId.toString());
        e.put("event_type", type);
        e.put("source", source);
        putIfText(e, "reason", truncate(reason));
        if (payload != null) {
            e.set("payload", payload);
        }
        crud.insert(EVENTS, e);
    }

    private ObjectNode forUpdate(UUID id) {
        return jdbc.sql("select row_to_json(t)::text from card_token t where id = ? for update").param(id)
                .query(String.class).optional().map(r -> (ObjectNode) json.readTree(r))
                .orElseThrow(() -> ApiException.notFound("card token", id));
    }

    private ObjectNode update(UUID id, String set) {
        return (ObjectNode) json.readTree(jdbc.sql("update card_token t set " + set + ", updated_by = ? where id = ? "
                        + "returning row_to_json(t)::text")
                .params(io.meridian.common.tenancy.TenantContext.principal(), id).query(String.class).single());
    }

    private Optional<ObjectNode> byRef(String ref) {
        return jdbc.sql("select row_to_json(t)::text from card_token t where network_token_ref = ?").param(ref)
                .query(String.class).optional().map(r -> (ObjectNode) json.readTree(r));
    }

    private int liveTokens(UUID cardId) {
        return jdbc.sql("select count(*) from card_token where card_id = ? "
                        + "and status in ('ACTIVE', 'SUSPENDED', 'PENDING_VERIFICATION', 'REQUESTED')")
                .param(cardId).query(Integer.class).single();
    }

    /** Serialises provisioning per card (token limit) with a transaction-scoped advisory lock. */
    private void lockCard(UUID cardId) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(?, 0))").param("card_token:" + cardId)
                .query(Object.class).list();
    }

    private OffsetDateTime dbNow() {
        return jdbc.sql("select now()").query(OffsetDateTime.class).single();
    }

    /** What the token service sent, minus card data (PAN, expiry, CVV are never stored). */
    private ObjectNode safePayload(JsonNode req) {
        ObjectNode p = json.createObjectNode();
        List<String> keep = new ArrayList<>(List.of("token_requestor", "token_requestor_id", "network_token_ref",
                "dpan_last4", "device_type", "device_name", "device_id_hash", "wallet_account_score", "device_score",
                "merchant_name"));
        for (String f : keep) {
            if (req.hasNonNull(f)) {
                p.set(f, req.get(f));
            }
        }
        return p;
    }

    private void device(ObjectNode v, JsonNode info) {
        if (info == null) {
            return;
        }
        putIfText(v, "device_type", info.path("device_type").asString(null));
        putIfText(v, "device_name", info.path("device_name").asString(null));
        String hash = info.path("device_id_hash").asString(null);
        if (hash == null && info.hasNonNull("device_id")) {
            hash = Crypto.sha256Hex(info.get("device_id").asString());
        }
        putIfText(v, "device_id_hash", hash);
        if (info.hasNonNull("wallet_account_score")) {
            v.put("wallet_account_score", info.get("wallet_account_score").asInt());
        }
        if (info.hasNonNull("device_score")) {
            v.put("device_score", info.get("device_score").asInt());
        }
    }

    private static Integer intOrNull(JsonNode n, String f) {
        return n.hasNonNull(f) ? n.get(f).asInt() : null;
    }

    private static void putIfText(ObjectNode n, String f, String v) {
        if (v != null && !v.isBlank()) {
            n.put(f, v);
        }
    }

    private static String truncate(String s) {
        return s == null ? null : s.length() > 255 ? s.substring(0, 255) : s;
    }

    private static String newTokenRef() {
        return "DNIT" + Crypto.randomHex(14).toUpperCase(java.util.Locale.ROOT);
    }
}
