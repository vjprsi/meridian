package io.meridian.cardauthorization.application;

import io.meridian.cardauthorization.config.AuthProperties;
import io.meridian.cardauthorization.domain.Periods;
import io.meridian.cardauthorization.infrastructure.AuthStore;
import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** After-the-decision lifecycle: clearing, expiry, stand-in replay, usage and sandbox simulations. */
@Service
public class LifecycleService {
    private static final Logger log = LoggerFactory.getLogger(LifecycleService.class);
    static final String T = AuthorizationService.T;
    private static final Set<String> OPEN = Set.of("APPROVED", "PARTIALLY_REVERSED", "PARTIALLY_CLEARED");

    private final AuthStore store;
    private final Events events;
    private final Counters counters;
    private final Ports.Ledger ledger;
    private final Ports.Clearing clearing;
    private final Ports.CardDirectory cards;
    private final AuthorizationService auth;
    private final AuthProperties props;
    private final TransactionTemplate tx;

    public LifecycleService(AuthStore store, Events events, Counters counters, Ports.Ledger ledger,
                            Ports.Clearing clearing, Ports.CardDirectory cards, AuthorizationService auth,
                            AuthProperties props, TransactionTemplate tx) {
        this.store = store;
        this.events = events;
        this.counters = counters;
        this.ledger = ledger;
        this.clearing = clearing;
        this.cards = cards;
        this.auth = auth;
        this.props = props;
        this.tx = tx;
    }

    // ------------------------------------------------------------------ clearing

    /** Records a presentment already captured by card-clearing-service. */
    public ObjectNode clear(UUID id, BigDecimal amount, boolean fin) {
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount", "amount must be positive");
        }
        return tx.execute(s -> {
            ObjectNode a = store.lock(T, id);
            String st = a.get("status").asString();
            if (!Set.of("APPROVED", "PARTIALLY_CLEARED", "PARTIALLY_REVERSED", "EXPIRED").contains(st)) {
                throw ApiException.conflict("invalid_transition", "authorization in status " + st + " cannot be cleared");
            }
            BigDecimal cleared = AuthorizationService.dec(a, "cleared_amount").add(amount);
            BigDecimal open = AuthorizationService.dec(a, "approved_amount").subtract(AuthorizationService.dec(a, "reversed_amount"));
            ObjectNode upd = store.json().createObjectNode();
            upd.put("cleared_amount", cleared.toPlainString());
            upd.put("status", fin || cleared.compareTo(open) >= 0 ? "CLEARED" : "PARTIALLY_CLEARED");
            ObjectNode saved = store.update(T, id, upd);
            events.publish(Events.CLEARED, T, saved);
            return saved;
        });
    }

    // ------------------------------------------------------------------ expiry

    public ObjectNode expire(UUID id, String reason) {
        ObjectNode a = store.get(T, id);
        String st = a.get("status").asString();
        if (!OPEN.contains(st)) {
            throw ApiException.conflict("invalid_transition", "authorization in status " + st + " cannot expire");
        }
        UUID hold = AuthorizationService.uuid(a, "hold_id");
        if (hold != null) {
            ledger.releaseHold(hold, reason == null ? "authorisation expired" : reason, "card-auth-expire:" + id);
        }
        return tx.execute(s -> {
            ObjectNode p = store.lock(T, id);
            if (!OPEN.contains(p.get("status").asString())) {
                throw ApiException.conflict("invalid_transition", "authorization is " + p.get("status").asString());
            }
            BigDecimal remaining = AuthorizationService.dec(p, "approved_amount")
                    .subtract(AuthorizationService.dec(p, "reversed_amount"))
                    .subtract(AuthorizationService.dec(p, "cleared_amount")).max(BigDecimal.ZERO);
            ObjectNode upd = store.json().createObjectNode();
            upd.put("status", "EXPIRED");
            if (reason != null) {
                upd.put("decline_detail", reason.length() > 255 ? reason.substring(0, 255) : reason);
            }
            ObjectNode saved = store.update(T, id, upd);
            boolean nothingCleared = AuthorizationService.dec(p, "cleared_amount").signum() == 0;
            counters.giveBack(p, remaining, nothingCleared && "APPROVED".equals(p.get("status").asString()) ? 1 : 0);
            events.publish(Events.EXPIRED, T, saved);
            return saved;
        });
    }

    public ObjectNode expireStale(OffsetDateTime asOf) {
        OffsetDateTime at = asOf != null ? asOf : store.now();
        List<ObjectNode> due = store.query("""
                select row_to_json(t)::text from card_authorization t
                where status in ('APPROVED', 'PARTIALLY_REVERSED', 'PARTIALLY_CLEARED') and expires_at < ?
                order by expires_at limit 1000""", at);
        int expired = 0;
        int failed = 0;
        for (ObjectNode a : due) {
            try {
                expire(UUID.fromString(a.get("id").asString()), "hold expired uncleared");
                expired++;
            } catch (RuntimeException e) {
                failed++;
                log.warn("expiry of authorization {} failed: {}", a.get("id").asString(), e.getMessage());
            }
        }
        ObjectNode out = store.json().createObjectNode();
        out.put("as_of", at.toString());
        out.put("candidates", due.size());
        out.put("expired", expired);
        out.put("failed", failed);
        return out;
    }

    // ------------------------------------------------------------------ usage

    public ObjectNode usage(UUID cardId) {
        OffsetDateTime now = store.now();
        ObjectNode out = store.json().createObjectNode();
        out.put("card_id", cardId.toString());
        out.put("as_of", now.toString());
        ArrayNode arr = out.putArray("counters");
        for (JsonNode c : store.counters(cardId)) {
            String period = c.get("period").asString();
            OffsetDateTime start = OffsetDateTime.parse(c.get("period_start").asString());
            if (!start.toInstant().equals(Periods.start(period, now).toInstant())) {
                continue;
            }
            ObjectNode o = arr.addObject();
            o.put("limit_ref", c.get("limit_ref").asString());
            o.put("period", period);
            o.put("channel", c.get("channel").asString());
            o.put("period_start", start.withOffsetSameInstant(ZoneOffset.UTC).toString());
            o.put("used_amount", new BigDecimal(c.get("used_amount").asString()).stripTrailingZeros().toPlainString());
            o.put("used_count", c.get("used_count").asInt());
            o.put("currency", c.get("currency").asString());
        }
        return out;
    }

    // ------------------------------------------------------------------ stand-in replay

    public ObjectNode replay() {
        List<ObjectNode> pending = store.query("""
                select row_to_json(s)::text from card_stand_in_record s where not replayed order by created_at limit 500""");
        int posted = 0;
        int overdrawn = 0;
        int failed = 0;
        for (ObjectNode si : pending) {
            UUID id = UUID.fromString(si.get("id").asString());
            UUID authId = UUID.fromString(si.get("card_authorization_id").asString());
            ObjectNode a = store.get(T, authId);
            UUID account = AuthorizationService.uuid(si, "ledger_account_id");
            String result;
            String error = null;
            UUID hold = null;
            if (account == null) {
                result = "FAILED";
                error = "no ledger account";
            } else {
                try {
                    hold = ledger.placeHold("stand-in-replay:" + id, account, AuthorizationService.dec(si, "amount")
                            .setScale(io.meridian.cardauthorization.domain.Fx.scale(si.get("currency").asString()), java.math.RoundingMode.HALF_EVEN),
                            si.get("currency").asString(), authId, ContextMapper.ts(a, "expires_at"),
                            "Card authorisation (stand-in replay)");
                    result = "POSTED";
                } catch (ApiException e) {
                    if (Ports.insufficientFunds(e)) {
                        result = "OVERDRAWN";
                        error = "insufficient funds at replay; follow up with the cardholder";
                    } else {
                        result = "FAILED";
                        error = e.code() + ": " + e.getMessage();
                    }
                } catch (RuntimeException e) {
                    result = "FAILED";
                    error = e.getClass().getSimpleName();
                }
            }
            String res = result;
            String err = error;
            UUID h = hold;
            tx.executeWithoutResult(s -> {
                ObjectNode upd = store.json().createObjectNode();
                upd.put("replay_result", res);
                if (err != null) {
                    upd.put("replay_error", err.length() > 255 ? err.substring(0, 255) : err);
                }
                if (!"FAILED".equals(res)) {
                    upd.put("replayed", true);
                    upd.put("replayed_at", store.now().toString());
                }
                if (h != null) {
                    upd.put("hold_id", h.toString());
                    ObjectNode au = store.json().createObjectNode();
                    au.put("hold_id", h.toString());
                    au.put("hold_account_id", account.toString());
                    store.update(T, authId, au);
                }
                ObjectNode saved = store.update("card_stand_in_record", id, upd);
                events.publish(Events.STAND_IN_REPLAYED, "card_stand_in_record", saved);
            });
            switch (res) {
                case "POSTED" -> posted++;
                case "OVERDRAWN" -> overdrawn++;
                default -> failed++;
            }
        }
        ObjectNode out = store.json().createObjectNode();
        out.put("replayed", posted + overdrawn);
        out.put("posted", posted);
        out.put("overdrawn", overdrawn);
        out.put("failed", failed);
        return out;
    }

    // ------------------------------------------------------------------ sandbox simulations

    public ObjectNode simulate(JsonNode b) {
        ObjectNode req = store.json().createObjectNode();
        String channel = AuthorizationService.text(b, "channel") == null ? "POS" : AuthorizationService.text(b, "channel");
        req.put("card_id", AuthorizationService.text(b, "card_id"));
        req.put("message_type", "AUTHORIZATION");
        req.put("mti", "0100");
        req.put("channel", channel);
        req.put("pos_entry_mode", AuthorizationService.text(b, "pos_entry_mode") != null ? AuthorizationService.text(b, "pos_entry_mode")
                : switch (channel) {
                    case "ECOM", "RECURRING" -> "ECOMMERCE";
                    case "MOTO" -> "MANUAL";
                    case "CONTACTLESS" -> "CONTACTLESS";
                    default -> "CHIP";
                });
        boolean present = !Set.of("ECOM", "MOTO", "RECURRING").contains(channel);
        req.put("card_present", present);
        req.put("cardholder_present", present);
        req.put("transaction_amount", AuthorizationService.text(b, "amount"));
        req.put("transaction_currency", AuthorizationService.text(b, "currency"));
        req.put("mcc", AuthorizationService.text(b, "mcc") == null ? "5999" : AuthorizationService.text(b, "mcc"));
        req.put("merchant_id", AuthorizationService.text(b, "merchant_id") == null ? "SIM-MERCHANT-1" : AuthorizationService.text(b, "merchant_id"));
        req.put("merchant_name", AuthorizationService.text(b, "merchant_name") == null ? "Sandbox Merchant" : AuthorizationService.text(b, "merchant_name"));
        if (AuthorizationService.text(b, "merchant_country") != null) {
            req.put("merchant_country", AuthorizationService.text(b, "merchant_country"));
        }
        if (b.hasNonNull("partial_approval_capable")) {
            req.put("partial_approval_capable", b.get("partial_approval_capable").asBoolean());
        }
        String digits = String.valueOf(Math.abs(Ids.newId().getLeastSignificantBits()));
        req.put("rrn", (digits + "000000000000").substring(0, 12));
        req.put("stan", (digits + "000000").substring(6, 12));
        req.put("network_transaction_id", "SIM" + Ids.newId().toString().replace("-", "").substring(0, 20));
        req.put("acquirer_id", "SIMACQ");
        req.put("terminal_id", "SIMTERM1");
        return auth.process(req);
    }

    public ObjectNode simulateReversal(UUID id, BigDecimal amount) {
        ObjectNode p = store.get(T, id);
        ObjectNode req = followUp(p, amount == null ? "REVERSAL" : "PARTIAL_REVERSAL", "0400");
        req.put("transaction_amount", amount == null ? p.get("transaction_amount").asString() : amount.toPlainString());
        return auth.process(req);
    }

    public ObjectNode simulateIncremental(UUID id, BigDecimal amount) {
        ObjectNode p = store.get(T, id);
        ObjectNode req = followUp(p, "INCREMENTAL", "0100");
        req.put("transaction_amount", amount.toPlainString());
        return auth.process(req);
    }

    private ObjectNode followUp(ObjectNode p, String type, String mti) {
        ObjectNode req = store.json().createObjectNode();
        req.put("card_id", p.get("card_id").asString());
        req.put("message_type", type);
        req.put("mti", mti);
        req.put("parent_authorization_id", p.get("id").asString());
        req.put("channel", p.get("channel").asString());
        req.put("transaction_currency", p.get("transaction_currency").asString());
        for (String f : List.of("rrn", "network_transaction_id", "merchant_id", "merchant_name", "mcc", "merchant_country",
                "pos_entry_mode", "acquirer_id", "terminal_id")) {
            if (p.hasNonNull(f)) {
                req.put(f, p.get(f).asString());
            }
        }
        return req;
    }

    /** Sends a one-record presentment for the authorisation through card-clearing-service. */
    public JsonNode simulateClearing(UUID id, BigDecimal amount, String key) {
        ObjectNode a = store.get(T, id);
        if (!OPEN.contains(a.get("status").asString())) {
            throw ApiException.conflict("invalid_transition", "authorization in status " + a.get("status").asString() + " cannot be cleared");
        }
        String scheme = props.defaultScheme();
        try {
            JsonNode ctx = cards.authContext(UUID.fromString(a.get("card_id").asString()));
            if (ctx.path("program").hasNonNull("scheme")) {
                scheme = ctx.path("program").get("scheme").asString();
            }
        } catch (RuntimeException e) {
            log.debug("scheme lookup failed, using default: {}", e.getMessage());
        }
        LocalDate date = OffsetDateTime.parse(a.get("created_at").asString()).withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
        BigDecimal billing = io.meridian.cardauthorization.domain.Fx.proportional(amount,
                AuthorizationService.dec(a, "transaction_amount"), AuthorizationService.dec(a, "billing_amount"),
                a.get("billing_currency").asString());
        ObjectNode file = store.json().createObjectNode();
        file.put("scheme", scheme);
        file.put("direction", "INCOMING");
        file.put("file_format", "JSON");
        file.put("file_name", "SIM-" + id + "-" + Ids.newId() + ".json");
        file.put("processing_date", store.now().withOffsetSameInstant(ZoneOffset.UTC).toLocalDate().toString());
        file.put("card_program_id", a.get("card_program_id").asString());
        ObjectNode rec = file.putArray("records").addObject();
        rec.put("record_type", "PRESENTMENT");
        rec.put("card_id", a.get("card_id").asString());
        for (String f : List.of("network_transaction_id", "rrn", "auth_code", "merchant_id", "merchant_name", "mcc", "merchant_country")) {
            if (a.hasNonNull(f)) {
                rec.put(f, a.get(f).asString());
            }
        }
        rec.put("transaction_date", date.toString());
        rec.put("transaction_amount", amount.toPlainString());
        rec.put("transaction_currency", a.get("transaction_currency").asString());
        rec.put("billing_amount", billing.toPlainString());
        rec.put("billing_currency", a.get("billing_currency").asString());
        rec.put("settlement_amount", billing.toPlainString());
        rec.put("settlement_currency", a.get("billing_currency").asString());
        JsonNode created = clearing.submitFile(file, "sim-clearing:" + id + ":" + key);
        JsonNode page = clearing.records(UUID.fromString(created.get("id").asString()));
        JsonNode first = page.path("data").path(0);
        if (first.isMissingNode()) {
            throw new ApiException(502, "upstream_error", "card-clearing-service returned no record");
        }
        return first;
    }
}
