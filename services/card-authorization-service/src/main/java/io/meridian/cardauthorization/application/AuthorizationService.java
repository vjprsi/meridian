package io.meridian.cardauthorization.application;

import io.meridian.cardauthorization.config.AuthProperties;
import io.meridian.cardauthorization.domain.AuthContext;
import io.meridian.cardauthorization.domain.AuthDecisionEngine;
import io.meridian.cardauthorization.domain.AuthDecisionEngine.Decision;
import io.meridian.cardauthorization.domain.AuthDecisionEngine.FraudResult;
import io.meridian.cardauthorization.domain.AuthDecisionEngine.VaultResult;
import io.meridian.cardauthorization.domain.Fx;
import io.meridian.cardauthorization.domain.Iso;
import io.meridian.cardauthorization.infrastructure.AuthStore;
import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The authorisation hot path: one ISO 8583 message in, one decided {@code card_authorization} row out.
 * Network I/O (card-service context, vault, fraud, JIT, ledger hold) happens before a single short transaction that
 * writes the authorisation, its counters, JIT / stand-in records and outbox events. Every downstream call carries
 * a key derived from the authorisation id, so a replay after a crash re-uses the same hold.
 */
@Service
public class AuthorizationService {
    private static final Logger log = LoggerFactory.getLogger(AuthorizationService.class);
    static final String T = "card_authorization";
    private static final Set<String> NO_FUNDS = Set.of("ACCOUNT_VERIFICATION", "BALANCE_INQUIRY", "REFUND_AUTH");
    private static final Set<String> SENSITIVE = Set.of("pan", "cvv", "cvv2", "cvc", "pin", "pin_block", "track",
            "track1", "track2", "encrypted_pin_block", "card_number", "expiry", "expiry_date");

    private final AuthStore store;
    private final Events events;
    private final Counters counters;
    private final Ports.CardDirectory cards;
    private final Ports.Ledger ledger;
    private final Ports.Vault vault;
    private final Ports.Jit jit;
    private final FraudGateway fraud;
    private final RuleCache rules;
    private final AuthDecisionEngine engine;
    private final AuthProperties props;
    private final TransactionTemplate tx;

    public AuthorizationService(AuthStore store, Events events, Counters counters, Ports.CardDirectory cards,
                                Ports.Ledger ledger, Ports.Vault vault, Ports.Jit jit, FraudGateway fraud,
                                RuleCache rules, AuthDecisionEngine engine, AuthProperties props, TransactionTemplate tx) {
        this.store = store;
        this.events = events;
        this.counters = counters;
        this.cards = cards;
        this.ledger = ledger;
        this.vault = vault;
        this.jit = jit;
        this.fraud = fraud;
        this.rules = rules;
        this.engine = engine;
        this.props = props;
        this.tx = tx;
    }

    /** Routes a network message by type. Returns the stored authorisation row (API shape). */
    public ObjectNode process(JsonNode req) {
        String type = Optional.ofNullable(text(req, "message_type")).orElse("AUTHORIZATION");
        return switch (type) {
            case "INCREMENTAL" -> incremental(req);
            case "REVERSAL", "PARTIAL_REVERSAL" -> reversal(req, type);
            case "ADVICE" -> advice(req);
            default -> authorize(req, type);
        };
    }

    // ================================================================== new authorisation

    ObjectNode authorize(JsonNode req, String type) {
        long t0 = System.nanoTime();
        UUID id = Ids.newId();
        UUID cardId = requireUuid(req, "card_id");
        BigDecimal txAmount = amount(req, type);
        String txCur = text(req, "transaction_currency");
        JsonNode ctxJson = cards.authContext(cardId);
        AuthStore.Snapshot snap = store.nowAndCounters(cardId);
        OffsetDateTime now = snap.now();
        JsonNode cardNode = ctxJson.path("card");
        JsonNode progNode = ctxJson.path("program");
        AuthContext.CardSnap card = ContextMapper.card(ctxJson);
        AuthContext.ProgramSnap program = ContextMapper.program(ctxJson);
        String billCur = program != null && program.currency() != null ? program.currency() : txCur;
        ObjectNode row = baseRow(req, id, type, ctxJson);
        row.put("billing_currency", billCur);
        boolean international = program != null && program.country() != null && text(req, "merchant_country") != null
                && !program.country().equals(text(req, "merchant_country"));
        row.put("is_international", international);

        BigDecimal rate = ContextMapper.dec(req, "conversion_rate");
        if (rate == null && !txCur.equals(billCur)) {
            rate = props.rate(txCur, billCur);
        }
        if (rate == null && !txCur.equals(billCur)) {
            row.put("billing_amount", "0");
            return decline(row, now, t0, "SYSTEM_ERROR", null, "no conversion rate " + txCur + "->" + billCur, null);
        }
        Fx.Billing bill = Fx.convert(txAmount, txCur, billCur, rate, ContextMapper.dec(progNode, "fx_markup_rate"));
        row.put("billing_amount", bill.amount().toPlainString());
        if (!txCur.equals(billCur)) {
            row.put("conversion_rate", bill.rate().toPlainString());
            row.put("fx_markup", bill.markup().toPlainString());
        }
        String channel = text(req, "channel");
        AuthContext.Request r = new AuthContext.Request(type, channel, text(req, "pos_entry_mode"), txAmount, txCur,
                bill.amount(), billCur, text(req, "mcc"), text(req, "merchant_id"), text(req, "merchant_country"),
                bool(req, "card_present"), international, text(req, "eci"), req.hasNonNull("cvv"),
                req.hasNonNull("expiry_month") && req.hasNonNull("expiry_year"), req.hasNonNull("encrypted_pin_block"),
                bool(req, "is_recurring"));
        AuthContext ctx = new AuthContext(now, card, program, ContextMapper.product(ctxJson),
                ContextMapper.controls(ctxJson), ContextMapper.limits(ctxJson),
                ContextMapper.usage(snap.counters(), now), r);
        int fraudTimeout = progNode.hasNonNull("fraud_timeout_ms") ? progNode.get("fraud_timeout_ms").asInt()
                : props.defaultFraudTimeoutMs();
        Decision d = engine.decide(ctx, program == null ? List.of() : rules.forProgram(program.id()),
                () -> verify(id, cardNode, req),
                () -> fraud.score(id, fraudBody(id, cardNode, bill.amount(), billCur, req), fraudTimeout));
        applyChecks(row, d);
        if (d.declined()) {
            return decline(row, now, t0, d.declineReason(), d.responseCode(), d.detail(), null);
        }
        Funding f = fund(id, type, cardNode, progNode, program, bill.amount(), billCur, bool(req, "partial_approval_capable"),
                now, row);
        if (!f.approved()) {
            return decline(row, now, t0, f.declineReason(), f.code(), f.detail(), f.jitLog());
        }
        return approve(row, ctx, now, t0, f);
    }

    private void applyChecks(ObjectNode row, Decision d) {
        if (d.vault() != null) {
            row.put("cvv_result", d.vault().cvv());
            if (!"NOT_PROCESSED".equals(d.vault().pin())) {
                row.put("pin_verified", "VERIFIED".equals(d.vault().pin()));
            }
        }
        if (d.fraud() != null) {
            if (d.fraud().score() != null) {
                row.put("fraud_score", d.fraud().score());
            }
            row.put("fraud_decision", d.fraud().decision());
            if (d.fraud().scoreId() != null) {
                row.put("fraud_score_id", d.fraud().scoreId().toString());
            }
        }
        if (d.ruleId() != null) {
            row.put("authorization_rule_id", d.ruleId().toString());
        }
    }

    // ------------------------------------------------------------------ funding

    record Funding(boolean approved, String declineReason, String code, String detail, String source, UUID holdId,
                   UUID holdAccount, BigDecimal amount, boolean partial, String standInReason, ObjectNode jitLog) {

        static Funding decline(String reason, String code, String detail, ObjectNode jitLog) {
            return new Funding(false, reason, code, detail, null, null, null, null, false, null, jitLog);
        }
    }

    private Funding fund(UUID id, String type, JsonNode cardNode, JsonNode progNode, AuthContext.ProgramSnap program,
                         BigDecimal billing, String cur, boolean partialCapable, OffsetDateTime now, ObjectNode row) {
        if (NO_FUNDS.contains(type) || billing.signum() == 0) {
            return new Funding(true, null, null, null, "NONE", null, null, billing, false, null, null);
        }
        int holdDays = progNode.hasNonNull("auth_hold_days") ? progNode.get("auth_hold_days").asInt() : props.defaultHoldDays();
        OffsetDateTime expires = now.plusDays(holdDays);
        row.put("expires_at", expires.toString());
        UUID cardAccount = ContextMapper.uuid(cardNode, "ledger_account_id");
        UUID progAccount = ContextMapper.uuid(progNode, "ledger_account_id");
        boolean isJit = program != null && "JIT".equals(program.fundingModel())
                || "JIT_PROGRAM".equals(ContextMapper.text(cardNode, "linked_account_type"));
        UUID account = cardAccount;
        String source = "LEDGER_HOLD";
        BigDecimal amount = billing;
        boolean partial = false;
        ObjectNode jitLog = null;
        String description = "Card authorisation " + Optional.ofNullable(text(row, "merchant_name")).orElse("");
        if (isJit) {
            String url = ContextMapper.text(progNode, "jit_webhook_url");
            if (url == null) {
                return Funding.decline("SYSTEM_ERROR", null, "JIT program without a webhook URL", null);
            }
            int timeout = progNode.hasNonNull("jit_timeout_ms") ? progNode.get("jit_timeout_ms").asInt() : 1500;
            ObjectNode payload = store.json().createObjectNode();
            payload.put("authorization_id", id.toString());
            payload.put("card_id", text(row, "card_id"));
            payload.put("card_program_id", text(row, "card_program_id"));
            payload.put("customer_id", text(row, "customer_id"));
            payload.put("amount", billing.toPlainString());
            payload.put("currency", cur);
            payload.put("transaction_amount", text(row, "transaction_amount"));
            payload.put("transaction_currency", text(row, "transaction_currency"));
            payload.put("mcc", text(row, "mcc"));
            payload.put("merchant_name", text(row, "merchant_name"));
            payload.put("merchant_country", text(row, "merchant_country"));
            payload.put("channel", text(row, "channel"));
            payload.put("partial_approval_capable", partialCapable);
            Ports.Jit.Response resp = jit.call(url, timeout, payload);
            jitLog = store.json().createObjectNode();
            jitLog.put("card_authorization_id", id.toString());
            jitLog.put("endpoint", url.length() > 500 ? url.substring(0, 500) : url);
            jitLog.put("request_amount", billing.toPlainString());
            jitLog.put("currency", cur);
            jitLog.put("result", resp.result());
            if (resp.approvedAmount() != null) {
                jitLog.put("approved_amount", resp.approvedAmount().toPlainString());
            }
            if (resp.httpStatus() != null) {
                jitLog.put("http_status", resp.httpStatus());
            }
            jitLog.put("latency_ms", resp.latencyMs());
            if (resp.body() != null) {
                jitLog.set("response_body", resp.body());
            }
            row.put("jit_used", true);
            row.put("jit_result", resp.result());
            row.put("jit_latency_ms", resp.latencyMs());
            switch (resp.result()) {
                case "APPROVED" -> {
                    if (resp.approvedAmount() != null && resp.approvedAmount().compareTo(billing) < 0) {
                        if (!partialCapable || resp.approvedAmount().signum() <= 0) {
                            return Funding.decline("JIT_DECLINED", null, "JIT approved only " + resp.approvedAmount().toPlainString(), jitLog);
                        }
                        amount = resp.approvedAmount();
                        partial = true;
                    }
                    account = progAccount != null ? progAccount : cardAccount;
                    source = "JIT";
                }
                case "DECLINED" -> {
                    return Funding.decline("JIT_DECLINED", null,
                            "declined by the program's JIT endpoint" + (resp.declineReason() == null ? "" : ": " + resp.declineReason()), jitLog);
                }
                default -> {
                    String reason = "TIMEOUT".equals(resp.result()) ? "JIT_TIMEOUT" : "JIT_ERROR";
                    return standIn(program, billing, reason, progAccount != null ? progAccount : cardAccount, jitLog,
                            Funding.decline("JIT_TIMEOUT", null, "JIT endpoint " + resp.result().toLowerCase(Locale.ROOT)
                                    + " after " + resp.latencyMs() + " ms", jitLog));
                }
            }
        }
        try {
            UUID hold = ledger.placeHold("card-auth-hold:" + id, account, amount, cur, id, expires, description);
            return new Funding(true, null, null, null, source, hold, account, amount, partial, null, jitLog);
        } catch (ApiException e) {
            if (Ports.insufficientFunds(e)) {
                if (partialCapable && !isJit) {
                    try {
                        BigDecimal available = ledger.available(account).setScale(Fx.scale(cur), RoundingMode.DOWN);
                        if (available.signum() > 0 && available.compareTo(amount) < 0) {
                            UUID hold = ledger.placeHold("card-auth-hold-partial:" + id, account, available, cur, id,
                                    expires, description);
                            return new Funding(true, null, null, null, source, hold, account, available, true, null, jitLog);
                        }
                    } catch (RuntimeException ex) {
                        log.warn("partial approval failed for authorization {}: {}", id, ex.getMessage());
                    }
                }
                return Funding.decline("INSUFFICIENT_FUNDS", null, "insufficient available balance", jitLog);
            }
            if (Ports.unavailable(e)) {
                return standIn(program, amount, "LEDGER_UNAVAILABLE", account, jitLog,
                        Funding.decline("SYSTEM_ERROR", Iso.ISSUER_UNAVAILABLE, "ledger unavailable and stand-in not allowed", jitLog));
            }
            return Funding.decline("OTHER", null, "ledger rejected the hold: " + e.code(), jitLog);
        } catch (RuntimeException e) {
            return standIn(program, amount, "LEDGER_UNAVAILABLE", account, jitLog,
                    Funding.decline("SYSTEM_ERROR", Iso.ISSUER_UNAVAILABLE, "ledger unavailable and stand-in not allowed", jitLog));
        }
    }

    private static Funding standIn(AuthContext.ProgramSnap program, BigDecimal amount, String reason, UUID account,
                                   ObjectNode jitLog, Funding otherwise) {
        boolean eligible = program != null && program.standInEnabled()
                && (program.standInMaxAmount() == null || amount.compareTo(program.standInMaxAmount()) <= 0);
        if (!eligible) {
            return otherwise;
        }
        return new Funding(true, null, null, null, "STAND_IN", null, account, amount, false, reason, jitLog);
    }

    // ------------------------------------------------------------------ persistence

    private ObjectNode approve(ObjectNode row, AuthContext ctx, OffsetDateTime now, long t0, Funding f) {
        row.put("decision", f.partial() ? "PARTIAL" : "APPROVED");
        row.put("response_code", f.partial() ? Iso.PARTIAL : Iso.APPROVED);
        row.put("auth_code", Iso.authCode());
        row.put("status", "APPROVED");
        row.put("approved_amount", f.amount().toPlainString());
        row.put("funding_source", f.source());
        if (f.holdId() != null) {
            row.put("hold_id", f.holdId().toString());
        }
        if (f.holdAccount() != null) {
            row.put("hold_account_id", f.holdAccount().toString());
        }
        row.put("stand_in", "STAND_IN".equals(f.source()));
        row.put("processing_time_ms", elapsed(t0));
        UUID cardId = UUID.fromString(row.get("card_id").asString());
        List<AuthContext.Limit> counted = engine.countedLimits(ctx);
        if (f.jitLog() == null && !"STAND_IN".equals(f.source())) {
            List<AuthStore.CounterDelta> deltas = new java.util.ArrayList<>();
            for (AuthContext.Limit l : counted) {
                deltas.add(new AuthStore.CounterDelta(cardId, l.id(), l.period(), l.channel(),
                        io.meridian.cardauthorization.domain.Periods.start(l.period(), now),
                        ctx.request().billingCurrency(), f.amount(), 1));
            }
            return store.insertDecided(row, Events.APPROVED, deltas);
        }
        return tx.execute(s -> {
            ObjectNode saved = store.insert(T, row);
            counters.add(cardId, counted, now, ctx.request().billingCurrency(), f.amount(), 1);
            writeJitLog(f.jitLog());
            if ("STAND_IN".equals(f.source())) {
                ObjectNode si = store.json().createObjectNode();
                si.put("card_authorization_id", saved.get("id").asString());
                si.put("card_id", cardId.toString());
                if (f.holdAccount() != null) {
                    si.put("ledger_account_id", f.holdAccount().toString());
                }
                si.put("amount", f.amount().toPlainString());
                si.put("currency", ctx.request().billingCurrency());
                si.put("stand_in_reason", f.standInReason());
                events.publish(Events.STAND_IN_APPROVED, "card_stand_in_record", store.insert("card_stand_in_record", si));
            }
            events.publish(Events.APPROVED, T, saved);
            return saved;
        });
    }

    private ObjectNode decline(ObjectNode row, OffsetDateTime now, long t0, String reason, String code, String detail,
                               ObjectNode jitLog) {
        row.put("decision", "DECLINED");
        row.put("status", "DECLINED");
        row.put("decline_reason", reason);
        row.put("response_code", code != null ? code : Iso.code(reason));
        if (detail != null) {
            row.put("decline_detail", detail.length() > 255 ? detail.substring(0, 255) : detail);
        }
        row.put("approved_amount", "0");
        row.put("funding_source", "NONE");
        row.put("processing_time_ms", elapsed(t0));
        if (jitLog == null) {
            return store.insertDecided(row, Events.DECLINED, List.of());
        }
        return tx.execute(s -> {
            ObjectNode saved = store.insert(T, row);
            writeJitLog(jitLog);
            events.publish(Events.DECLINED, T, saved);
            return saved;
        });
    }

    private void writeJitLog(ObjectNode jitLog) {
        if (jitLog != null) {
            events.publish(Events.JIT_REQUESTED, "card_jit_funding_request", store.insert("card_jit_funding_request", jitLog));
        }
    }

    /** Columns copied from the network message (request-only and sensitive fields never reach the row). */
    private ObjectNode baseRow(JsonNode req, UUID id, String type, JsonNode ctxJson) {
        ObjectNode row = store.crud().toColumns(T, req);
        row.put("id", id.toString());
        row.put("message_type", type);
        if (!row.hasNonNull("mti")) {
            row.put("mti", switch (type) {
                case "FINANCIAL" -> "0200";
                case "REVERSAL", "PARTIAL_REVERSAL" -> "0400";
                case "ADVICE" -> "0120";
                default -> "0100";
            });
        }
        if (req.hasNonNull("raw_message")) {
            row.set("raw_message", sanitize(req.get("raw_message")));
        }
        JsonNode card = ctxJson.path("card");
        row.put("card_program_id", card.get("card_program_id").asString());
        if (card.hasNonNull("customer_id")) {
            row.put("customer_id", card.get("customer_id").asString());
        }
        return row;
    }

    /** Removes PAN / CVV / PIN / track data from a parsed ISO message, recursively. */
    static JsonNode sanitize(JsonNode n) {
        JsonNode copy = n.deepCopy();
        strip(copy);
        return copy;
    }

    private static void strip(JsonNode n) {
        if (n instanceof ObjectNode o) {
            Iterator<String> it = o.propertyNames().iterator();
            java.util.List<String> remove = new java.util.ArrayList<>();
            while (it.hasNext()) {
                String k = it.next();
                String lk = k.toLowerCase(Locale.ROOT);
                if (SENSITIVE.contains(lk) || lk.contains("pan") && !lk.contains("company") || lk.equals("de2")
                        || lk.equals("de35") || lk.equals("de45") || lk.equals("de52")) {
                    remove.add(k);
                } else {
                    strip(o.get(k));
                }
            }
            remove.forEach(o::remove);
        } else if (n != null && n.isArray()) {
            n.forEach(AuthorizationService::strip);
        }
    }

    private VaultResult verify(UUID id, JsonNode cardNode, JsonNode req) {
        ObjectNode b = store.json().createObjectNode();
        b.put("pan_token", ContextMapper.text(cardNode, "pan_token"));
        for (String f : List.of("cvv", "encrypted_pin_block", "pin_key_id")) {
            if (req.hasNonNull(f)) {
                b.put(f, req.get(f).asString());
            }
        }
        if (req.hasNonNull("expiry_month") && req.hasNonNull("expiry_year")) {
            b.put("expiry_month", req.get("expiry_month").asInt());
            b.put("expiry_year", req.get("expiry_year").asInt());
        }
        try {
            JsonNode r = vault.verify(b, "auth-verify:" + id);
            return new VaultResult(r.path("cvv_result").asString("P"), r.path("expiry_result").asString("P"),
                    r.path("pin_result").asString("NOT_PROCESSED"));
        } catch (RuntimeException e) {
            log.warn("vault verification unavailable for authorization {}: {}", id, e.getClass().getSimpleName());
            // cannot verify: treat CVV / PIN as not matching (never approve unverified sensitive data)
            return new VaultResult(req.hasNonNull("cvv") ? "N" : "P", "P",
                    req.hasNonNull("encrypted_pin_block") ? "INVALID" : "NOT_PROCESSED");
        }
    }

    private ObjectNode fraudBody(UUID id, JsonNode cardNode, BigDecimal amount, String cur, JsonNode req) {
        ObjectNode b = store.json().createObjectNode();
        b.put("subject_type", "CARD_AUTHORIZATION");
        b.put("subject_id", id.toString());
        putIf(b, "customer_id", ContextMapper.text(cardNode, "customer_id"));
        putIf(b, "card_id", ContextMapper.text(cardNode, "id"));
        putIf(b, "account_id", ContextMapper.text(cardNode, "ledger_account_id"));
        b.put("amount", amount.toPlainString());
        b.put("currency", cur);
        b.put("channel", switch (Optional.ofNullable(text(req, "channel")).orElse("POS")) {
            case "ECOM", "MOTO", "RECURRING" -> "ECOMMERCE";
            case "ATM" -> "ATM";
            case "CONTACTLESS" -> "CONTACTLESS";
            default -> "POS";
        });
        ObjectNode c = b.putObject("context");
        putIf(c, "mcc", text(req, "mcc"));
        putIf(c, "merchant_id", text(req, "merchant_id"));
        putIf(c, "merchant_country", text(req, "merchant_country"));
        putIf(c, "pos_entry_mode", text(req, "pos_entry_mode"));
        return b;
    }

    // ================================================================== incremental

    ObjectNode incremental(JsonNode req) {
        long t0 = System.nanoTime();
        UUID id = Ids.newId();
        UUID parentId = requireUuid(req, "parent_authorization_id");
        BigDecimal delta = amount(req, "INCREMENTAL");
        if (delta.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount", "incremental amount must be positive");
        }
        Optional<ObjectNode> parentOpt = store.find(T, parentId);
        UUID cardId = requireUuid(req, "card_id");
        JsonNode ctxJson = cards.authContext(cardId);
        AuthStore.Snapshot snap = store.nowAndCounters(cardId);
        OffsetDateTime now = snap.now();
        ObjectNode row = baseRow(req, id, "INCREMENTAL", ctxJson);
        AuthContext.ProgramSnap program = ContextMapper.program(ctxJson);
        if (parentOpt.isEmpty() || !parentOpt.get().get("card_id").asString().equals(cardId.toString())) {
            row.put("billing_currency", program != null ? program.currency() : text(req, "transaction_currency"));
            row.put("billing_amount", "0");
            row.remove("parent_authorization_id"); // unknown original: keep it out of the foreign key
            return decline(row, now, t0, "ORIGINAL_NOT_FOUND", null, "original authorization " + parentId + " not found", null);
        }
        ObjectNode parent = parentOpt.get();
        String billCur = parent.get("billing_currency").asString();
        row.put("billing_currency", billCur);
        row.put("is_international", parent.get("is_international").asBoolean());
        if (!parent.get("transaction_currency").asString().equals(text(req, "transaction_currency"))) {
            throw ApiException.unprocessable("currency_mismatch", "incremental currency must equal the original's");
        }
        BigDecimal billDelta = Fx.proportional(delta, dec(parent, "transaction_amount"), dec(parent, "billing_amount"), billCur)
                .setScale(Fx.scale(billCur), RoundingMode.HALF_EVEN);
        row.put("billing_amount", billDelta.toPlainString());
        String ps = parent.get("status").asString();
        if (!Set.of("APPROVED", "PARTIALLY_CLEARED").contains(ps)) {
            return decline(row, now, t0, "OTHER", "12", "original authorization is " + ps, null);
        }
        AuthContext.Request r = new AuthContext.Request("INCREMENTAL", parent.get("channel").asString(),
                text(parent, "pos_entry_mode"), delta, text(req, "transaction_currency"), billDelta, billCur,
                text(parent, "mcc"), text(parent, "merchant_id"), text(parent, "merchant_country"),
                parent.get("card_present").asBoolean(), parent.get("is_international").asBoolean(), text(parent, "eci"),
                false, false, false, parent.get("is_recurring").asBoolean());
        AuthContext ctx = new AuthContext(now, ContextMapper.card(ctxJson), program, ContextMapper.product(ctxJson),
                ContextMapper.controls(ctxJson), ContextMapper.limits(ctxJson), ContextMapper.usage(snap.counters(), now), r);
        int fraudTimeout = ctxJson.path("program").hasNonNull("fraud_timeout_ms")
                ? ctxJson.path("program").get("fraud_timeout_ms").asInt() : props.defaultFraudTimeoutMs();
        Decision d = engine.decide(ctx, program == null ? List.of() : rules.forProgram(program.id()),
                () -> VaultResult.NOT_PROCESSED,
                () -> fraud.score(id, fraudBody(id, ctxJson.path("card"), billDelta, billCur, parent), fraudTimeout));
        applyChecks(row, d);
        if (d.declined()) {
            return decline(row, now, t0, d.declineReason(), d.responseCode(), d.detail(), null);
        }
        UUID holdId = uuid(parent, "hold_id");
        String source = "LEDGER_HOLD";
        if (holdId != null) {
            try {
                ledger.incrementHold(holdId, billDelta, "incremental authorisation " + id, "card-auth-incr:" + id);
            } catch (ApiException e) {
                if (Ports.insufficientFunds(e)) {
                    return decline(row, now, t0, "INSUFFICIENT_FUNDS", null, "insufficient funds for the increment", null);
                }
                if (!Ports.unavailable(e) || standIn(program, billDelta, "LEDGER_UNAVAILABLE", null, null, null) == null) {
                    return decline(row, now, t0, "SYSTEM_ERROR", Iso.ISSUER_UNAVAILABLE, "ledger unavailable", null);
                }
                source = "STAND_IN";
            }
        } else {
            source = "STAND_IN".equals(text(parent, "funding_source")) ? "STAND_IN" : "NONE";
        }
        String fundingSource = source;
        int holdDays = ctxJson.path("program").hasNonNull("auth_hold_days")
                ? ctxJson.path("program").get("auth_hold_days").asInt() : props.defaultHoldDays();
        List<AuthContext.Limit> counted = engine.countedLimits(ctx);
        row.put("decision", "APPROVED");
        row.put("response_code", Iso.APPROVED);
        row.put("auth_code", text(parent, "auth_code"));
        row.put("status", "APPROVED");
        row.put("approved_amount", billDelta.toPlainString());
        row.put("funding_source", fundingSource);
        row.put("stand_in", "STAND_IN".equals(fundingSource));
        if (holdId != null) {
            row.put("hold_id", holdId.toString());
            row.put("hold_account_id", text(parent, "hold_account_id"));
        }
        row.put("processing_time_ms", elapsed(t0));
        return tx.execute(s -> {
            ObjectNode p = store.lock(T, parentId);
            ObjectNode upd = store.json().createObjectNode();
            upd.put("transaction_amount", dec(p, "transaction_amount").add(delta).toPlainString());
            upd.put("billing_amount", dec(p, "billing_amount").add(billDelta).toPlainString());
            upd.put("approved_amount", dec(p, "approved_amount").add(billDelta).toPlainString());
            upd.put("expires_at", now.plusDays(holdDays).toString());
            store.update(T, parentId, upd);
            ObjectNode saved = store.insert(T, row);
            counters.add(UUID.fromString(text(p, "card_id")), counted, now, billCur, billDelta, 0);
            if ("STAND_IN".equals(fundingSource) && holdId != null) {
                ObjectNode si = store.json().createObjectNode();
                si.put("card_authorization_id", saved.get("id").asString());
                si.put("card_id", text(p, "card_id"));
                si.put("ledger_account_id", text(p, "hold_account_id"));
                si.put("amount", billDelta.toPlainString());
                si.put("currency", billCur);
                si.put("stand_in_reason", "LEDGER_UNAVAILABLE");
                events.publish(Events.STAND_IN_APPROVED, "card_stand_in_record", store.insert("card_stand_in_record", si));
            }
            events.publish(Events.INCREMENTED, T, saved);
            return saved;
        });
    }

    // ================================================================== reversal

    ObjectNode reversal(JsonNode req, String type) {
        long t0 = System.nanoTime();
        UUID id = Ids.newId();
        UUID parentId = requireUuid(req, "parent_authorization_id");
        UUID cardId = requireUuid(req, "card_id");
        Optional<ObjectNode> parentOpt = store.find(T, parentId);
        if (parentOpt.isEmpty() || !parentOpt.get().get("card_id").asString().equals(cardId.toString())) {
            JsonNode ctxJson = cards.authContext(cardId);
            ObjectNode row = baseRow(req, id, type, ctxJson);
            AuthContext.ProgramSnap program = ContextMapper.program(ctxJson);
            row.put("billing_currency", program != null ? program.currency() : text(req, "transaction_currency"));
            row.put("billing_amount", "0");
            row.remove("parent_authorization_id"); // unknown original: keep it out of the foreign key
            return decline(row, store.now(), t0, "ORIGINAL_NOT_FOUND", null, "original authorization " + parentId + " not found", null);
        }
        ObjectNode parent = parentOpt.get();
        ObjectNode row = store.crud().toColumns(T, req);
        row.put("id", id.toString());
        row.put("message_type", type);
        if (!row.hasNonNull("mti")) {
            row.put("mti", "0400");
        }
        row.put("card_program_id", text(parent, "card_program_id"));
        if (parent.hasNonNull("customer_id")) {
            row.put("customer_id", text(parent, "customer_id"));
        }
        if (req.hasNonNull("raw_message")) {
            row.set("raw_message", sanitize(req.get("raw_message")));
        }
        String billCur = text(parent, "billing_currency");
        row.put("billing_currency", billCur);
        row.put("is_international", parent.get("is_international").asBoolean());
        if (!row.hasNonNull("transaction_currency")) {
            row.put("transaction_currency", text(parent, "transaction_currency"));
        }
        if (!row.hasNonNull("channel")) {
            row.put("channel", text(parent, "channel"));
        }
        String ps = text(parent, "status");
        OffsetDateTime now = store.now();
        if ("REVERSED".equals(ps)) {
            row.put("billing_amount", "0");
            row.put("transaction_amount", "0");
            return persistReversalNoop(row, t0, "original already reversed");
        }
        if (!Set.of("APPROVED", "PARTIALLY_REVERSED", "PARTIALLY_CLEARED").contains(ps)) {
            row.put("billing_amount", "0");
            return decline(row, now, t0, "OTHER", "12", "original authorization is " + ps, null);
        }
        BigDecimal approved = dec(parent, "approved_amount");
        BigDecimal remaining = approved.subtract(dec(parent, "reversed_amount")).subtract(dec(parent, "cleared_amount"));
        BigDecimal txReq = ContextMapper.dec(req, "transaction_amount");
        BigDecimal amount = txReq == null || txReq.signum() <= 0 ? remaining
                : Fx.proportional(txReq, dec(parent, "transaction_amount"), dec(parent, "billing_amount"), billCur).min(remaining);
        amount = amount.setScale(Fx.scale(billCur), RoundingMode.HALF_EVEN);
        boolean full = amount.compareTo(remaining) >= 0;
        if (amount.signum() <= 0) {
            row.put("billing_amount", "0");
            return persistReversalNoop(row, t0, "nothing left to reverse");
        }
        UUID holdId = uuid(parent, "hold_id");
        UUID holdAccount = uuid(parent, "hold_account_id");
        UUID newHold = null;
        if (holdId != null) {
            ledger.releaseHold(holdId, (full ? "reversal " : "partial reversal ") + id, "card-auth-release:" + id);
            if (!full) {
                newHold = ledger.placeHold("card-auth-rehold:" + id, holdAccount,
                        remaining.subtract(amount).setScale(Fx.scale(billCur), RoundingMode.HALF_EVEN), billCur,
                        parentId, ContextMapper.ts(parent, "expires_at"), "Card authorisation (after partial reversal)");
            }
        }
        UUID rehold = newHold;
        BigDecimal reversed = amount;
        row.put("billing_amount", reversed.toPlainString());
        if (!row.hasNonNull("transaction_amount")) {
            row.put("transaction_amount", reversed.toPlainString());
        }
        row.put("decision", "APPROVED");
        row.put("response_code", Iso.APPROVED);
        row.put("auth_code", text(parent, "auth_code"));
        row.put("status", "REVERSED");
        row.put("approved_amount", reversed.toPlainString());
        row.put("funding_source", "NONE");
        row.put("processing_time_ms", elapsed(t0));
        return tx.execute(s -> {
            ObjectNode p = store.lock(T, parentId);
            BigDecimal rem = dec(p, "approved_amount").subtract(dec(p, "reversed_amount")).subtract(dec(p, "cleared_amount"));
            BigDecimal amt = reversed.min(rem);
            ObjectNode upd = store.json().createObjectNode();
            upd.put("reversed_amount", dec(p, "reversed_amount").add(amt).toPlainString());
            boolean nowFull = amt.compareTo(rem) >= 0;
            upd.put("status", nowFull ? (dec(p, "cleared_amount").signum() > 0 ? "CLEARED" : "REVERSED") : "PARTIALLY_REVERSED");
            if (rehold != null) {
                upd.put("hold_id", rehold.toString());
            }
            ObjectNode updated = store.update(T, parentId, upd);
            row.put("parent_authorization_id", parentId.toString());
            ObjectNode saved = store.insert(T, row);
            boolean countBack = nowFull && dec(p, "cleared_amount").signum() == 0;
            counters.giveBack(p, amt, countBack ? 1 : 0);
            adjustStandIn(parentId, amt);
            events.publish(Events.REVERSED, T, updated);
            return saved;
        });
    }

    private ObjectNode persistReversalNoop(ObjectNode row, long t0, String detail) {
        row.put("decision", "APPROVED");
        row.put("response_code", Iso.APPROVED);
        row.put("status", "REVERSED");
        row.put("approved_amount", "0");
        row.put("funding_source", "NONE");
        row.put("decline_detail", detail);
        row.put("processing_time_ms", elapsed(t0));
        return tx.execute(s -> store.insert(T, row));
    }

    /** A reversal before a stand-in approval was replayed reduces what the replay will hold. */
    private void adjustStandIn(UUID authId, BigDecimal reversed) {
        store.queryOne("select row_to_json(s)::text from card_stand_in_record s where card_authorization_id = ? and not replayed for update", authId)
                .ifPresent(si -> {
                    BigDecimal left = dec(si, "amount").subtract(reversed).max(BigDecimal.ZERO);
                    ObjectNode upd = store.json().createObjectNode();
                    upd.put("amount", left.toPlainString());
                    if (left.signum() == 0) {
                        upd.put("replayed", true);
                        upd.put("replay_error", "reversed before replay");
                    }
                    store.update("card_stand_in_record", UUID.fromString(si.get("id").asString()), upd);
                });
    }

    // ================================================================== advice

    /** 0120 advice: the network approved on the issuer's behalf (STIP); record it and reserve funds best-effort. */
    ObjectNode advice(JsonNode req) {
        long t0 = System.nanoTime();
        UUID id = Ids.newId();
        UUID cardId = requireUuid(req, "card_id");
        BigDecimal txAmount = amount(req, "ADVICE");
        JsonNode ctxJson = cards.authContext(cardId);
        AuthStore.Snapshot snap = store.nowAndCounters(cardId);
        OffsetDateTime now = snap.now();
        AuthContext.ProgramSnap program = ContextMapper.program(ctxJson);
        String txCur = text(req, "transaction_currency");
        String billCur = program != null && program.currency() != null ? program.currency() : txCur;
        ObjectNode row = baseRow(req, id, "ADVICE", ctxJson);
        BigDecimal rate = ContextMapper.dec(req, "conversion_rate");
        if (rate == null) {
            rate = props.rate(txCur, billCur);
        }
        Fx.Billing bill = txCur.equals(billCur) || rate != null
                ? Fx.convert(txAmount, txCur, billCur, rate, ContextMapper.dec(ctxJson.path("program"), "fx_markup_rate"))
                : new Fx.Billing(txAmount, BigDecimal.ZERO, BigDecimal.ONE);
        row.put("billing_currency", billCur);
        row.put("billing_amount", bill.amount().toPlainString());
        UUID account = ContextMapper.uuid(ctxJson.path("card"), "ledger_account_id");
        int holdDays = ctxJson.path("program").hasNonNull("auth_hold_days")
                ? ctxJson.path("program").get("auth_hold_days").asInt() : props.defaultHoldDays();
        UUID hold = null;
        try {
            hold = ledger.placeHold("card-auth-hold:" + id, account, bill.amount(), billCur, id, now.plusDays(holdDays),
                    "Card authorisation advice (network stand-in)");
        } catch (RuntimeException e) {
            log.warn("advice {}: hold not placed ({})", id, e.getMessage());
        }
        AuthContext.Request r = new AuthContext.Request("ADVICE", text(req, "channel"), text(req, "pos_entry_mode"), txAmount,
                txCur, bill.amount(), billCur, text(req, "mcc"), text(req, "merchant_id"), text(req, "merchant_country"),
                bool(req, "card_present"), false, null, false, false, false, false);
        AuthContext ctx = new AuthContext(now, ContextMapper.card(ctxJson), program, null, List.of(),
                ContextMapper.limits(ctxJson), java.util.Map.of(), r);
        row.put("expires_at", now.plusDays(holdDays).toString());
        return approve(row, ctx, now, t0, new Funding(true, null, null, null, hold == null ? "NONE" : "LEDGER_HOLD", hold,
                hold == null ? null : account, bill.amount(), false, null, null));
    }

    // ================================================================== helpers

    private static BigDecimal amount(JsonNode req, String type) {
        BigDecimal a = ContextMapper.dec(req, "transaction_amount");
        if (a == null) {
            throw ApiException.badRequest("missing_parameter", "transaction_amount is required");
        }
        if (a.signum() < 0 || a.signum() == 0 && !NO_FUNDS.contains(type)) {
            throw ApiException.unprocessable("invalid_amount", "transaction_amount must be positive");
        }
        if (req.hasNonNull("transaction_currency") && !req.get("transaction_currency").asString().matches("[A-Z]{3}")) {
            throw ApiException.badRequest("invalid_currency", "transaction_currency must be ISO 4217");
        }
        if (!req.hasNonNull("transaction_currency")) {
            throw ApiException.badRequest("missing_parameter", "transaction_currency is required");
        }
        return a;
    }

    private static UUID requireUuid(JsonNode n, String f) {
        String s = text(n, f);
        if (s == null) {
            throw ApiException.badRequest("missing_parameter", f + " is required");
        }
        return UUID.fromString(s);
    }

    static String text(JsonNode n, String f) {
        return ContextMapper.text(n, f);
    }

    static UUID uuid(JsonNode n, String f) {
        return ContextMapper.uuid(n, f);
    }

    static BigDecimal dec(JsonNode n, String f) {
        BigDecimal d = ContextMapper.dec(n, f);
        return d == null ? BigDecimal.ZERO : d;
    }

    private static boolean bool(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v != null && !v.isNull() && v.asBoolean();
    }

    private static void putIf(ObjectNode n, String f, String v) {
        if (v != null) {
            n.put(f, v);
        }
    }

    private static int elapsed(long t0) {
        return (int) ((System.nanoTime() - t0) / 1_000_000);
    }
}
