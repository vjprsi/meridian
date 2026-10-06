package io.meridian.cardauthorization.domain;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The pre-funding authorisation decision. Checks run in a fixed order and the first failure wins:
 * <ol>
 *   <li>program and card status, card expiry (database clock), product channel flags</li>
 *   <li>controls (MCC, merchant, country, channel, currency, amount, time window)</li>
 *   <li>spend limits and velocity (current-period counters)</li>
 *   <li>vault verification of CVV2 / expiry / PIN, only when those were sent (lazy)</li>
 *   <li>authorisation rules not referring to the fraud score (priority order)</li>
 *   <li>fraud score (lazy; fail-open or fail-closed per program), then rules that use the fraud score</li>
 * </ol>
 * Funding (ledger hold / JIT / stand-in) is decided by the application afterwards. Pure: the two suppliers are the
 * only side effects and are invoked only when the stage is reached.
 */
public final class AuthDecisionEngine {

    /** Vault verification: M match, N no match, P not processed; pin VERIFIED, INVALID, TRIES_EXCEEDED, NOT_SET, NOT_PROCESSED. */
    public record VaultResult(String cvv, String expiry, String pin) {
        public static final VaultResult NOT_PROCESSED = new VaultResult("P", "P", "NOT_PROCESSED");
    }

    /** Fraud evaluation; {@code available=false} when it timed out or failed. */
    public record FraudResult(boolean available, Integer score, String decision, UUID scoreId) {
        public static FraudResult unavailable() {
            return new FraudResult(false, null, "UNAVAILABLE", null);
        }
    }

    /** One rule evaluation (for the rules test endpoint and the decision record). */
    public record RuleHit(UUID ruleId, String name, int priority, boolean matched, String action, String error) {}

    public record Decision(boolean approved, String declineReason, String responseCode, String detail, UUID ruleId,
                           String ruleAction, VaultResult vault, FraudResult fraud, List<AuthContext.Limit> limits) {

        public boolean declined() {
            return !approved;
        }
    }

    private static final Set<String> GOOD_ECI = Set.of("01", "02", "05", "06");

    /** Full decision. {@code rules} must already be filtered to the program (+ global) and sorted by priority. */
    public Decision decide(AuthContext c, List<AuthRule> rules, Supplier<VaultResult> vault, Supplier<FraudResult> fraud) {
        Builder b = new Builder();
        if (b.fail(checkStatus(c)) || b.fail(checkControls(c)) || b.fail(checkLimits(c))) {
            return b.build(c);
        }
        AuthContext.Request r = c.request();
        if ("ATM".equals(r.channel()) && !r.hasPin() && c.product() != null && !"NONE".equals(c.product().pinPolicy())) {
            return b.declineNow("INVALID_PIN", null, "ATM withdrawal without a PIN block", c);
        }
        if (r.hasCvv() || r.hasExpiry() || r.hasPin()) {
            b.vault = vault.get();
            if (b.fail(checkVault(b.vault))) {
                return b.build(c);
            }
        }
        Map<String, Object> vars = variables(c, null);
        List<AuthRule> pre = new ArrayList<>();
        List<AuthRule> post = new ArrayList<>();
        for (AuthRule rule : rules) {
            if (rule.effectiveAt(c.now())) {
                (rule.needsFraudScore() ? post : pre).add(rule);
            }
        }
        if (b.fail(applyRules(pre, vars, r, b))) {
            return b.build(c);
        }
        b.fraud = fraud.get();
        if (b.fail(checkFraud(c, b.fraud))) {
            return b.build(c);
        }
        if (!post.isEmpty()) {
            vars.put("fraud_score", b.fraud.available() ? b.fraud.score() : null);
            if (b.fail(applyRules(post, vars, r, b))) {
                return b.build(c);
            }
        }
        return b.build(c);
    }

    // ------------------------------------------------------------------ stage 1: status

    public Fail checkStatus(AuthContext c) {
        if (c.program() != null && !"ACTIVE".equals(c.program().status())) {
            return new Fail("PROGRAM_SUSPENDED", null, "program is " + c.program().status());
        }
        String s = c.card().status();
        Fail statusFail = switch (s) {
            case "ACTIVE" -> null;
            case "CREATED" -> new Fail("CARD_INACTIVE", "78", "card is not activated");
            case "FROZEN" -> new Fail("CARD_FROZEN", null, "card is frozen");
            case "BLOCKED" -> new Fail("CARD_BLOCKED", null, "card is blocked");
            case "LOST" -> new Fail("CARD_LOST", null, "card reported lost");
            case "STOLEN" -> new Fail("CARD_STOLEN", null, "card reported stolen");
            case "EXPIRED" -> new Fail("CARD_EXPIRED", null, "card is expired");
            default -> new Fail("CARD_INACTIVE", "14", "card is " + s);
        };
        if (statusFail != null) {
            return statusFail;
        }
        var utc = c.now().withOffsetSameInstant(ZoneOffset.UTC);
        int nowIdx = utc.getYear() * 12 + utc.getMonthValue();
        int expIdx = c.card().expiryYear() * 12 + c.card().expiryMonth();
        if (expIdx < nowIdx) {
            return new Fail("CARD_EXPIRED", null, "card expired " + c.card().expiryMonth() + "/" + c.card().expiryYear());
        }
        AuthContext.ProductSnap p = c.product();
        AuthContext.Request r = c.request();
        if (p != null) {
            if ("ATM".equals(r.channel()) && !p.atmEnabled()) {
                return new Fail("CONTROL_CHANNEL", null, "ATM withdrawals are disabled on the product");
            }
            if (("ECOM".equals(r.channel()) || "MOTO".equals(r.channel())) && !p.ecomEnabled()) {
                return new Fail("CONTROL_CHANNEL", null, "card-not-present transactions are disabled on the product");
            }
            if (("CONTACTLESS".equals(r.channel()) || "CONTACTLESS".equals(r.posEntryMode())) && !p.contactlessEnabled()) {
                return new Fail("CONTROL_CHANNEL", null, "contactless is disabled on the product");
            }
            if (r.international() && !p.internationalEnabled()) {
                return new Fail("CONTROL_COUNTRY", null, "cross-border transactions are disabled on the product");
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ stage 2: controls

    public Fail checkControls(AuthContext c) {
        AuthContext.Request r = c.request();
        for (AuthContext.Control k : c.controls()) {
            if (!k.appliesAt(c.now(), r.channel())) {
                continue;
            }
            Set<String> v = k.values() == null ? Set.of() : k.values();
            Fail f = switch (k.type()) {
                case "MCC_ALLOW" -> r.mcc() == null || !v.contains(r.mcc())
                        ? new Fail("CONTROL_MCC", null, "MCC " + r.mcc() + " is not allowed") : null;
                case "MCC_DENY" -> r.mcc() != null && v.contains(r.mcc())
                        ? new Fail("CONTROL_MCC", null, "MCC " + r.mcc() + " is blocked") : null;
                case "MERCHANT_ALLOW" -> r.merchantId() == null || !v.contains(r.merchantId().toUpperCase())
                        ? new Fail("CONTROL_MERCHANT", null, "merchant is not allowed") : null;
                case "MERCHANT_DENY" -> r.merchantId() != null && v.contains(r.merchantId().toUpperCase())
                        ? new Fail("CONTROL_MERCHANT", null, "merchant " + r.merchantId() + " is blocked") : null;
                case "COUNTRY_ALLOW" -> r.merchantCountry() == null || !v.contains(r.merchantCountry())
                        ? new Fail("CONTROL_COUNTRY", null, "country " + r.merchantCountry() + " is not allowed") : null;
                case "COUNTRY_DENY" -> r.merchantCountry() != null && v.contains(r.merchantCountry())
                        ? new Fail("CONTROL_COUNTRY", null, "country " + r.merchantCountry() + " is blocked") : null;
                case "CHANNEL_ALLOW" -> !v.contains(r.channel())
                        ? new Fail("CONTROL_CHANNEL", null, "channel " + r.channel() + " is not allowed") : null;
                case "CHANNEL_DENY" -> v.contains(r.channel())
                        ? new Fail("CONTROL_CHANNEL", null, "channel " + r.channel() + " is blocked") : null;
                case "CURRENCY_ALLOW" -> !v.contains(r.transactionCurrency())
                        ? new Fail("CONTROL_CURRENCY", null, "currency " + r.transactionCurrency() + " is not allowed") : null;
                case "AMOUNT_MAX" -> amountMax(k, r);
                case "TIME_WINDOW" -> timeWindow(k, c);
                default -> null;
            };
            if (f != null) {
                return f;
            }
        }
        return null;
    }

    private static Fail amountMax(AuthContext.Control k, AuthContext.Request r) {
        if (k.maxAmount() == null) {
            return null;
        }
        BigDecimal amount = k.currency() == null || k.currency().equals(r.billingCurrency()) ? r.billingAmount()
                : k.currency().equals(r.transactionCurrency()) ? r.transactionAmount() : null;
        if (amount != null && amount.compareTo(k.maxAmount()) > 0) {
            return new Fail("CONTROL_AMOUNT", null, "amount exceeds the per-transaction maximum " + k.maxAmount().toPlainString());
        }
        return null;
    }

    private static Fail timeWindow(AuthContext.Control k, AuthContext c) {
        ZonedDateTime local = c.now().atZoneSameInstant(k.zone() == null ? ZoneOffset.UTC : k.zone());
        if (k.days() != null && !k.days().isEmpty() && !k.days().contains(local.getDayOfWeek())) {
            return new Fail("CONTROL_TIME", null, "not allowed on " + local.getDayOfWeek());
        }
        if (k.start() != null && k.end() != null) {
            LocalTime t = local.toLocalTime();
            boolean inside = k.start().isBefore(k.end())
                    ? !t.isBefore(k.start()) && t.isBefore(k.end())
                    : !t.isBefore(k.start()) || t.isBefore(k.end()); // overnight window
            if (!inside) {
                return new Fail("CONTROL_TIME", null, "outside the allowed time window " + k.start() + "-" + k.end());
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ stage 3: limits

    public Fail checkLimits(AuthContext c) {
        AuthContext.Request r = c.request();
        for (AuthContext.Limit l : c.limits()) {
            if (!l.appliesTo(r.channel(), r.billingCurrency())) {
                continue;
            }
            if (!Periods.cumulative(l.period())) {
                if (l.maxAmount() != null && r.billingAmount().compareTo(l.maxAmount()) > 0) {
                    return new Fail("LIMIT_EXCEEDED", null, "per-transaction limit " + l.maxAmount().toPlainString() + " exceeded");
                }
                continue;
            }
            AuthContext.Usage u = c.usageOf(l);
            if (l.maxAmount() != null && u.amount().add(r.billingAmount()).compareTo(l.maxAmount()) > 0) {
                return new Fail("LIMIT_EXCEEDED", null, l.period() + " limit " + l.maxAmount().toPlainString()
                        + " exceeded (used " + u.amount().stripTrailingZeros().toPlainString() + ")");
            }
            if (l.maxCount() != null && u.count() + 1 > l.maxCount()) {
                return new Fail("VELOCITY_EXCEEDED", null, l.period() + " count limit " + l.maxCount() + " reached");
            }
        }
        return null;
    }

    /** Limits whose counters an approval increments. */
    public List<AuthContext.Limit> countedLimits(AuthContext c) {
        List<AuthContext.Limit> out = new ArrayList<>();
        for (AuthContext.Limit l : c.limits()) {
            if (l.appliesTo(c.request().channel(), c.request().billingCurrency()) && Periods.cumulative(l.period())) {
                out.add(l);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ stage 4: vault

    public Fail checkVault(VaultResult v) {
        if (v == null) {
            return null;
        }
        if ("TRIES_EXCEEDED".equals(v.pin())) {
            return new Fail("PIN_TRIES_EXCEEDED", null, "PIN try limit reached");
        }
        if ("INVALID".equals(v.pin()) || "NOT_SET".equals(v.pin())) {
            return new Fail("INVALID_PIN", null, "NOT_SET".equals(v.pin()) ? "no PIN set on the card" : "incorrect PIN");
        }
        if ("N".equals(v.cvv())) {
            return new Fail("INVALID_CVV", null, "CVV2 mismatch");
        }
        if ("N".equals(v.expiry())) {
            return new Fail("INVALID_EXPIRY", null, "expiry date mismatch");
        }
        return null;
    }

    // ------------------------------------------------------------------ stage 5: rules

    private Fail applyRules(List<AuthRule> rules, Map<String, Object> vars, AuthContext.Request r, Builder b) {
        for (AuthRule rule : rules) {
            if (!rule.condition().evaluate(vars)) {
                continue;
            }
            switch (rule.action()) {
                case "APPROVE" -> {
                    b.rule(rule);
                    return null;
                }
                case "DECLINE" -> {
                    b.rule(rule);
                    String reason = rule.declineReason() == null ? "RULE_DECLINED" : rule.declineReason();
                    return new Fail(reason, null, "declined by rule '" + rule.name() + "'");
                }
                case "STEP_UP" -> {
                    b.rule(rule);
                    if (("ECOM".equals(r.channel()) || "RECURRING".equals(r.channel())) && (r.eci() == null || !GOOD_ECI.contains(r.eci()))) {
                        return new Fail("THREEDS_FAILED", null, "rule '" + rule.name() + "' requires 3-D Secure authentication");
                    }
                }
                default -> b.rule(rule); // REVIEW: approve but flag
            }
        }
        return null;
    }

    /** Evaluates rules one by one for the test endpoint (no short-circuit, errors captured). */
    public List<RuleHit> explain(List<AuthRule> rules, Map<String, Object> vars) {
        List<RuleHit> out = new ArrayList<>();
        for (AuthRule rule : rules) {
            try {
                out.add(new RuleHit(rule.id(), rule.name(), rule.priority(), rule.condition().evaluate(vars), rule.action(), null));
            } catch (RuntimeException e) {
                out.add(new RuleHit(rule.id(), rule.name(), rule.priority(), false, rule.action(), e.getMessage()));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ stage 6: fraud

    public Fail checkFraud(AuthContext c, FraudResult f) {
        if (f == null) {
            return null;
        }
        if (!f.available()) {
            if (c.program() != null && "FAIL_CLOSED".equals(c.program().fraudPolicy())) {
                return new Fail("FRAUD_SUSPECTED", null, "fraud scoring unavailable (program policy FAIL_CLOSED)");
            }
            return null;
        }
        int threshold = c.program() == null ? 1001 : c.program().fraudDeclineScore();
        if ("BLOCK".equals(f.decision()) || f.score() != null && f.score() >= threshold) {
            return new Fail("FRAUD_SUSPECTED", null, "fraud score " + f.score() + " (" + f.decision() + ")");
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    /** Variables visible to rule conditions. */
    public static Map<String, Object> variables(AuthContext c, Integer fraudScore) {
        AuthContext.Request r = c.request();
        Map<String, Object> v = new HashMap<>();
        v.put("amount", r.billingAmount());
        v.put("transaction_amount", r.transactionAmount());
        v.put("currency", r.billingCurrency());
        v.put("transaction_currency", r.transactionCurrency());
        v.put("mcc", r.mcc());
        v.put("merchant_id", r.merchantId());
        v.put("merchant_country", r.merchantCountry());
        v.put("channel", r.channel());
        v.put("pos_entry_mode", r.posEntryMode());
        v.put("is_international", r.international());
        v.put("card_present", r.cardPresent());
        v.put("is_recurring", r.recurring());
        v.put("message_type", r.messageType());
        v.put("hour", c.now().withOffsetSameInstant(ZoneOffset.UTC).getHour());
        v.put("fraud_score", fraudScore);
        return v;
    }

    /** A failed check: internal reason, optional DE39 override and a human-readable detail. */
    public record Fail(String reason, String code, String detail) {}

    private static final class Builder {
        Fail fail;
        VaultResult vault;
        FraudResult fraud;
        UUID ruleId;
        String ruleAction;

        boolean fail(Fail f) {
            if (f != null) {
                fail = f;
                return true;
            }
            return false;
        }

        void rule(AuthRule r) {
            ruleId = r.id();
            ruleAction = r.action();
        }

        Decision declineNow(String reason, String code, String detail, AuthContext c) {
            fail = new Fail(reason, code, detail);
            return build(c);
        }

        Decision build(AuthContext c) {
            if (fail == null) {
                return new Decision(true, null, Iso.APPROVED, null, ruleId, ruleAction, vault, fraud, List.of());
            }
            return new Decision(false, fail.reason(), fail.code() != null ? fail.code() : Iso.code(fail.reason()),
                    fail.detail(), ruleId, ruleAction, vault, fraud, List.of());
        }
    }
}
