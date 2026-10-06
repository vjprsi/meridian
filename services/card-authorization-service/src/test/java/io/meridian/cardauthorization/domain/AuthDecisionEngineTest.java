package io.meridian.cardauthorization.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.cardauthorization.domain.AuthDecisionEngine.Decision;
import io.meridian.cardauthorization.domain.AuthDecisionEngine.FraudResult;
import io.meridian.cardauthorization.domain.AuthDecisionEngine.VaultResult;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class AuthDecisionEngineTest {
    final AuthDecisionEngine engine = new AuthDecisionEngine();
    static final FraudResult LOW = new FraudResult(true, 120, "ALLOW", UUID.randomUUID());

    Decision decide(Ctx c) {
        return engine.decide(c.build(), List.of(), () -> VaultResult.NOT_PROCESSED, () -> LOW);
    }

    Decision decide(Ctx c, List<AuthRule> rules, VaultResult v, FraudResult f) {
        return engine.decide(c.build(), rules, () -> v, () -> f);
    }

    static AuthRule rule(String expr, String action, String reason, int priority) {
        return new AuthRule(UUID.randomUUID(), null, "r" + priority, priority, Expression.parse(expr), action, reason, true, null, null);
    }

    @Test
    void approvesBaseline() {
        Decision d = decide(Ctx.base());
        assertThat(d.approved()).isTrue();
        assertThat(d.responseCode()).isEqualTo("00");
        assertThat(d.fraud().score()).isEqualTo(120);
    }

    @Test
    void cardStatusesMapToIsoCodes() {
        assertThat(decide(Ctx.base().status("CREATED")).responseCode()).isEqualTo("78");
        assertThat(decide(Ctx.base().status("FROZEN"))).extracting(Decision::declineReason, Decision::responseCode)
                .containsExactly("CARD_FROZEN", "57");
        assertThat(decide(Ctx.base().status("BLOCKED")).responseCode()).isEqualTo("62");
        assertThat(decide(Ctx.base().status("LOST")).responseCode()).isEqualTo("41");
        assertThat(decide(Ctx.base().status("STOLEN")).responseCode()).isEqualTo("43");
        assertThat(decide(Ctx.base().status("CLOSED"))).extracting(Decision::declineReason, Decision::responseCode)
                .containsExactly("CARD_INACTIVE", "14");
        assertThat(decide(Ctx.base().status("EXPIRED")).responseCode()).isEqualTo("54");
    }

    @Test
    void programSuspendedDeclines() {
        Ctx c = Ctx.base();
        c.programStatus = "SUSPENDED";
        assertThat(decide(c)).extracting(Decision::declineReason, Decision::responseCode).containsExactly("PROGRAM_SUSPENDED", "62");
    }

    @Test
    void expiryUsesTheGivenClock() {
        Ctx c = Ctx.base();
        c.expYear = 2026;
        c.expMonth = 9;
        assertThat(decide(c).declineReason()).isEqualTo("CARD_EXPIRED");
        c.expMonth = 10; // valid through the end of the expiry month
        assertThat(decide(c).approved()).isTrue();
    }

    @Test
    void productFlags() {
        Ctx c = Ctx.base().channel("ATM");
        c.product = new AuthContext.ProductSnap("ACTIVE", "REQUIRED", true, false, true, true);
        assertThat(decide(c).declineReason()).isEqualTo("CONTROL_CHANNEL");
        Ctx i = Ctx.base();
        i.international = true;
        i.product = new AuthContext.ProductSnap("ACTIVE", "REQUIRED", true, true, true, false);
        assertThat(decide(i)).extracting(Decision::declineReason, Decision::responseCode).containsExactly("CONTROL_COUNTRY", "57");
    }

    @Test
    void controls() {
        assertThat(decide(Ctx.base().control("MCC_DENY", "5411")).declineReason()).isEqualTo("CONTROL_MCC");
        assertThat(decide(Ctx.base().control("MCC_ALLOW", "5812")).declineReason()).isEqualTo("CONTROL_MCC");
        assertThat(decide(Ctx.base().control("MCC_ALLOW", "5411")).approved()).isTrue();
        assertThat(decide(Ctx.base().control("COUNTRY_DENY", "US")).declineReason()).isEqualTo("CONTROL_COUNTRY");
        assertThat(decide(Ctx.base().control("MERCHANT_DENY", "M1")).declineReason()).isEqualTo("CONTROL_MERCHANT");
        assertThat(decide(Ctx.base().control("CHANNEL_DENY", "POS")).declineReason()).isEqualTo("CONTROL_CHANNEL");
        assertThat(decide(Ctx.base().control("CURRENCY_ALLOW", "EUR")).declineReason()).isEqualTo("CONTROL_CURRENCY");
        assertThat(decide(Ctx.base().amountMax("20")))
                .extracting(Decision::declineReason, Decision::responseCode).containsExactly("CONTROL_AMOUNT", "61");
    }

    @Test
    void timeWindowInItsTimezone() {
        // 12:00Z = 08:00 in New York (EDT)
        assertThat(decide(Ctx.base().window(Set.of(), "09:00", "17:00", "America/New_York")).declineReason()).isEqualTo("CONTROL_TIME");
        assertThat(decide(Ctx.base().window(Set.of(), "07:00", "17:00", "America/New_York")).approved()).isTrue();
        assertThat(decide(Ctx.base().window(Set.of(DayOfWeek.SATURDAY), "00:00", "23:59", "UTC")).declineReason()).isEqualTo("CONTROL_TIME");
        assertThat(decide(Ctx.base().window(Set.of(), "22:00", "13:00", "UTC")).approved()).isTrue(); // overnight window
    }

    @Test
    void spendLimitsAndVelocity() {
        assertThat(decide(Ctx.base().limit("L1", "DAILY", "100", null, "80", 2)))
                .extracting(Decision::declineReason, Decision::responseCode).containsExactly("LIMIT_EXCEEDED", "61");
        assertThat(decide(Ctx.base().limit("L1", "DAILY", "100", null, "75", 2)).approved()).isTrue();
        assertThat(decide(Ctx.base().limit("L2", "DAILY", null, 3, "10", 3)))
                .extracting(Decision::declineReason, Decision::responseCode).containsExactly("VELOCITY_EXCEEDED", "65");
        assertThat(decide(Ctx.base().limit("L3", "PER_TRANSACTION", "10", null, null, 0)).declineReason()).isEqualTo("LIMIT_EXCEEDED");
        // counters of an older period do not count
        Ctx stale = Ctx.base().limit("L4", "DAILY", "30", null, null, 0);
        stale.usage.put(AuthContext.usageKey("L4", Periods.start("DAILY", stale.now.minusDays(1))), new AuthContext.Usage(new BigDecimal("29"), 1));
        assertThat(decide(stale).approved()).isTrue();
    }

    @Test
    void channelSpecificLimitAppliesOnlyToItsChannel() {
        Ctx c = Ctx.base();
        c.limits.add(new AuthContext.Limit("ATM", "DAILY", "ATM", new BigDecimal("10"), null, "USD", true));
        assertThat(decide(c).approved()).isTrue();
        c.channel("ATM").pin = true;
        assertThat(decide(c).declineReason()).isEqualTo("LIMIT_EXCEEDED");
    }

    @Test
    void vaultResults() {
        Ctx c = Ctx.base();
        c.cvv = true;
        assertThat(decide(c, List.of(), new VaultResult("N", "P", "NOT_PROCESSED"), LOW).responseCode()).isEqualTo("N7");
        assertThat(decide(c, List.of(), new VaultResult("M", "P", "NOT_PROCESSED"), LOW).approved()).isTrue();
        c.pin = true;
        assertThat(decide(c, List.of(), new VaultResult("M", "P", "INVALID"), LOW).responseCode()).isEqualTo("55");
        assertThat(decide(c, List.of(), new VaultResult("M", "P", "TRIES_EXCEEDED"), LOW).responseCode()).isEqualTo("75");
        c.expiry = true;
        assertThat(decide(c, List.of(), new VaultResult("M", "N", "VERIFIED"), LOW).declineReason()).isEqualTo("INVALID_EXPIRY");
    }

    @Test
    void vaultNotCalledWithoutSensitiveInputAndAtmNeedsPin() {
        AtomicInteger calls = new AtomicInteger();
        engine.decide(Ctx.base().build(), List.of(), () -> {
            calls.incrementAndGet();
            return VaultResult.NOT_PROCESSED;
        }, () -> LOW);
        assertThat(calls).hasValue(0);
        assertThat(decide(Ctx.base().channel("ATM")).declineReason()).isEqualTo("INVALID_PIN");
    }

    @Test
    void rulesDeclineApproveAndStepUp() {
        AuthRule gambling = rule("mcc in ['7995', '7800'] && amount > 10", "DECLINE", null, 10);
        assertThat(decide(Ctx.base().mcc("7995"), List.of(gambling), null, LOW))
                .extracting(Decision::declineReason, Decision::responseCode, Decision::ruleId)
                .containsExactly("RULE_DECLINED", "05", gambling.id());
        AuthRule allow = rule("merchant_id == 'M1'", "APPROVE", null, 1);
        assertThat(decide(Ctx.base().mcc("7995"), List.of(allow, gambling), null, LOW).approved()).isTrue();
        AuthRule stepUp = rule("channel == 'ECOM'", "STEP_UP", null, 5);
        assertThat(decide(Ctx.base().channel("ECOM"), List.of(stepUp), null, LOW).declineReason()).isEqualTo("THREEDS_FAILED");
        Ctx withEci = Ctx.base().channel("ECOM");
        withEci.eci = "05";
        assertThat(decide(withEci, List.of(stepUp), null, LOW).approved()).isTrue();
        AuthRule custom = rule("is_international", "DECLINE", "CONTROL_COUNTRY", 3);
        Ctx intl = Ctx.base();
        intl.international = true;
        assertThat(decide(intl, List.of(custom), null, LOW).declineReason()).isEqualTo("CONTROL_COUNTRY");
    }

    @Test
    void fraudRulesRunAfterScoring() {
        AuthRule hiScore = rule("fraud_score >= 500 && amount > 20", "DECLINE", "FRAUD_SUSPECTED", 1);
        assertThat(decide(Ctx.base(), List.of(hiScore), null, new FraudResult(true, 600, "REVIEW", null)).declineReason())
                .isEqualTo("FRAUD_SUSPECTED");
        assertThat(decide(Ctx.base(), List.of(hiScore), null, LOW).approved()).isTrue();
    }

    @Test
    void fraudThresholdAndPolicy() {
        assertThat(decide(Ctx.base(), List.of(), null, new FraudResult(true, 850, "REVIEW", null)))
                .extracting(Decision::declineReason, Decision::responseCode).containsExactly("FRAUD_SUSPECTED", "59");
        assertThat(decide(Ctx.base(), List.of(), null, new FraudResult(true, 10, "BLOCK", null)).declineReason()).isEqualTo("FRAUD_SUSPECTED");
        assertThat(decide(Ctx.base(), List.of(), null, FraudResult.unavailable()).approved()).isTrue();
        Ctx closed = Ctx.base();
        closed.fraudPolicy = "FAIL_CLOSED";
        assertThat(decide(closed, List.of(), null, FraudResult.unavailable()).declineReason()).isEqualTo("FRAUD_SUSPECTED");
    }

    @Test
    void firstFailureWinsAndLaterStagesAreSkipped() {
        AtomicInteger fraudCalls = new AtomicInteger();
        Ctx c = Ctx.base().status("FROZEN").control("MCC_DENY", "5411").limit("L", "DAILY", "1", null, "0", 0);
        Decision d = engine.decide(c.build(), List.of(), () -> VaultResult.NOT_PROCESSED, () -> {
            fraudCalls.incrementAndGet();
            return LOW;
        });
        assertThat(d.declineReason()).isEqualTo("CARD_FROZEN");
        assertThat(fraudCalls).hasValue(0);
        assertThat(decide(Ctx.base().control("MCC_DENY", "5411").limit("L", "DAILY", "1", null, "0", 0)).declineReason())
                .isEqualTo("CONTROL_MCC");
    }

    // ------------------------------------------------------------------ properties

    @Provide
    Arbitrary<String> blockedStatus() {
        return Arbitraries.of("CREATED", "FROZEN", "BLOCKED", "LOST", "STOLEN", "EXPIRED", "CLOSED", "REPLACED");
    }

    @Property(tries = 200)
    void anyNonActiveStatusAlwaysDeclines(@ForAll("blockedStatus") String status,
                                          @ForAll @BigRange(min = "0.01", max = "100000") BigDecimal amount) {
        Decision d = decide(Ctx.base().status(status).amount(amount.setScale(2, java.math.RoundingMode.HALF_EVEN).max(new BigDecimal("0.01")).toPlainString()));
        assertThat(d.approved()).isFalse();
        assertThat(d.responseCode()).isNotEqualTo("00");
    }

    @Property(tries = 300)
    void amountAboveAmountMaxAlwaysDeclines(@ForAll @IntRange(min = 1, max = 100_000) int maxCents,
                                            @ForAll @IntRange(min = 1, max = 100_000) int overCents) {
        BigDecimal max = BigDecimal.valueOf(maxCents, 2);
        BigDecimal amount = max.add(BigDecimal.valueOf(overCents, 2));
        Decision d = decide(Ctx.base().amountMax(max.toPlainString()).amount(amount.toPlainString()));
        assertThat(d.declineReason()).isEqualTo("CONTROL_AMOUNT");
    }

    @Property(tries = 300)
    void denyListedMccNeverApproves(@ForAll @IntRange(min = 0, max = 9999) int mcc,
                                    @ForAll @IntRange(min = 1, max = 5) int extra) {
        String m = String.format("%04d", mcc);
        List<String> deny = new ArrayList<>(List.of(m));
        for (int i = 0; i < extra; i++) {
            deny.add(String.format("%04d", (mcc + i * 13 + 1) % 10000));
        }
        Decision d = decide(Ctx.base().mcc(m).control("MCC_DENY", deny.toArray(String[]::new)));
        assertThat(d.approved()).isFalse();
    }

    @Property(tries = 300)
    void dailyLimitNeverExceededByAnApproval(@ForAll @IntRange(min = 0, max = 50_000) int usedCents,
                                             @ForAll @IntRange(min = 1, max = 50_000) int amountCents,
                                             @ForAll @IntRange(min = 1, max = 60_000) int limitCents) {
        BigDecimal used = BigDecimal.valueOf(usedCents, 2);
        BigDecimal amount = BigDecimal.valueOf(amountCents, 2);
        BigDecimal limit = BigDecimal.valueOf(limitCents, 2);
        Decision d = decide(Ctx.base().amount(amount.toPlainString()).limit("D", "DAILY", limit.toPlainString(), null, used.toPlainString(), 1));
        if (d.approved()) {
            assertThat(used.add(amount)).isLessThanOrEqualTo(limit);
        } else {
            assertThat(used.add(amount)).isGreaterThan(limit);
        }
    }

    // ------------------------------------------------------------------ micro-benchmark

    @Test
    void thousandSequentialDecisionsAreFast() {
        List<AuthRule> rules = List.of(rule("mcc in ['7995'] && amount > 500", "DECLINE", null, 1),
                rule("channel == 'ECOM' && is_international", "STEP_UP", null, 2),
                rule("fraud_score > 900", "DECLINE", null, 3));
        Ctx c = Ctx.base().control("MCC_DENY", "6051", "4829").control("COUNTRY_DENY", "KP", "IR")
                .amountMax("5000").limit("D", "DAILY", "100000", 10_000, "10", 1).limit("M", "MONTHLY", "500000", null, "10", 1);
        AuthContext ctx = c.build();
        for (int i = 0; i < 2_000; i++) { // warm-up
            engine.decide(ctx, rules, () -> VaultResult.NOT_PROCESSED, () -> LOW);
        }
        long[] nanos = new long[1_000];
        long t0 = System.nanoTime();
        for (int i = 0; i < nanos.length; i++) {
            long s = System.nanoTime();
            Decision d = engine.decide(ctx, rules, () -> VaultResult.NOT_PROCESSED, () -> LOW);
            nanos[i] = System.nanoTime() - s;
            assertThat(d.approved()).isTrue();
        }
        long totalMs = (System.nanoTime() - t0) / 1_000_000;
        java.util.Arrays.sort(nanos);
        System.out.printf("engine path: 1000 decisions in %d ms, p50=%.3f ms p99=%.3f ms max=%.3f ms%n", totalMs,
                nanos[499] / 1e6, nanos[989] / 1e6, nanos[999] / 1e6);
        assertThat(totalMs).isLessThan(1_000);
    }
}
