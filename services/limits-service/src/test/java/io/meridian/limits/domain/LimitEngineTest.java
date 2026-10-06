package io.meridian.limits.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.limits.domain.LimitModel.CheckRequest;
import io.meridian.limits.domain.LimitModel.Limit;
import io.meridian.limits.domain.LimitModel.Rule;
import io.meridian.limits.domain.LimitModel.RuleResult;
import io.meridian.limits.domain.LimitModel.Usage;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

class LimitEngineTest {
    static final UUID CUSTOMER = UUID.randomUUID();
    static final UUID ACCOUNT = UUID.randomUUID();

    static Rule rule(String code, String scope, String ref, String txType, String period, String measure, String max,
                     Integer count, String action, int priority, boolean regulatory) {
        return new Rule(UUID.randomUUID(), code, scope, ref, txType, null, period, measure, "USD",
                max == null ? null : new BigDecimal(max), count, action, priority, regulatory);
    }

    static CheckRequest req(String amount, String txType) {
        return new CheckRequest("ACCOUNT", ACCOUNT, CUSTOMER, "T1", "RETAIL", null, txType, "MOBILE_APP",
                new BigDecimal(amount), "USD", null);
    }

    @Test
    void mostSpecificRuleWinsWithinAGroupAndRegulatoryAlwaysApplies() {
        Rule global = rule("G", "GLOBAL", null, null, "DAILY", "AMOUNT", "1000", null, "REJECT", 100, false);
        Rule tier = rule("T", "KYC_TIER", "T1", null, "DAILY", "AMOUNT", "2000", null, "REJECT", 100, false);
        Rule cust = rule("C", "CUSTOMER", CUSTOMER.toString(), null, "DAILY", "AMOUNT", "5000", null, "REJECT", 100, false);
        Rule reg = rule("R", "GLOBAL", null, null, "DAILY", "AMOUNT", "9000", null, "REJECT", 100, true);
        Rule cashSub = rule("CASH", "GLOBAL", null, "CASH_OUT", "DAILY", "AMOUNT", "300", null, "REJECT", 100, false);
        Rule acct = rule("A", "ACCOUNT", ACCOUNT.toString(), null, "DAILY", "AMOUNT", "700", null, "REJECT", 100, false);
        Rule otherTier = rule("T2", "KYC_TIER", "T2", null, "DAILY", "AMOUNT", "1", null, "REJECT", 100, false);
        var sel = LimitEngine.select(List.of(global, tier, cust, reg, cashSub, acct, otherTier), req("10", "CASH_OUT"));
        assertThat(sel.effective()).extracting(a -> a.rule().code()).containsExactlyInAnyOrder("C", "R", "CASH", "A");
        assertThat(sel.superseded()).extracting(s -> s.rule().code() + ">" + s.by().code())
                .containsExactlyInAnyOrder("G>C", "T>C");
        var acctApplicable = sel.effective().stream().filter(a -> a.rule().code().equals("A")).findFirst().orElseThrow();
        assertThat(acctApplicable.countingSubjectType()).isEqualTo("ACCOUNT");
        assertThat(acctApplicable.countingSubjectId()).isEqualTo(ACCOUNT);
        var custApplicable = sel.effective().stream().filter(a -> a.rule().code().equals("C")).findFirst().orElseThrow();
        assertThat(custApplicable.countingSubjectId()).isEqualTo(CUSTOMER);
    }

    @Test
    void priorityBreaksTiesAndCurrencyMismatchIsExcluded() {
        Rule a = rule("A", "GLOBAL", null, null, "DAILY", "AMOUNT", "100", null, "REJECT", 20, false);
        Rule b = rule("B", "GLOBAL", null, null, "DAILY", "AMOUNT", "200", null, "REJECT", 10, false);
        Rule eur = new Rule(UUID.randomUUID(), "EUR", "GLOBAL", null, null, null, "WEEKLY", "AMOUNT", "EUR",
                new BigDecimal("5"), null, "REJECT", 1, false);
        var sel = LimitEngine.select(List.of(a, b, eur), req("10", "P2P"));
        assertThat(sel.effective()).extracting(x -> x.rule().code()).containsExactly("B");
        assertThat(sel.currencyMismatch()).extracting(Rule::code).containsExactly("EUR");
    }

    @Test
    void periodWindowsAreUtcCalendarPeriods() {
        Instant now = Instant.parse("2026-10-07T15:42:10Z"); // a Wednesday
        assertThat(LimitEngine.window("DAILY", now).start()).isEqualTo(Instant.parse("2026-10-07T00:00:00Z"));
        assertThat(LimitEngine.window("WEEKLY", now).start()).isEqualTo(Instant.parse("2026-10-05T00:00:00Z"));
        assertThat(LimitEngine.window("WEEKLY", now).end()).isEqualTo(Instant.parse("2026-10-12T00:00:00Z"));
        assertThat(LimitEngine.window("MONTHLY", now).end()).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
        assertThat(LimitEngine.window("YEARLY", now).start()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        var rolling = LimitEngine.window("ROLLING_24H", now);
        assertThat(rolling.rolling()).isTrue();
        assertThat(rolling.start()).isEqualTo(Instant.parse("2026-10-07T15:00:00Z"));
        assertThat(rolling.lookbackStart()).isEqualTo(Instant.parse("2026-10-06T16:00:00Z"));
    }

    @Property(tries = 500)
    void amountArithmetic(@ForAll @LongRange(min = 0, max = 1_000_000) long used,
                          @ForAll @LongRange(min = 0, max = 1_000_000) long reserved,
                          @ForAll @LongRange(min = 1, max = 1_000_000) long amount,
                          @ForAll @LongRange(min = 0, max = 3_000_000) long limit) {
        Rule r = rule("D", "GLOBAL", null, null, "DAILY", "AMOUNT", Long.toString(limit), null, "REJECT", 1, false);
        Usage u = new Usage(BigDecimal.valueOf(used), BigDecimal.valueOf(reserved), 0, 0);
        RuleResult res = LimitEngine.evaluate(r, new Limit(BigDecimal.valueOf(limit), null, null), u,
                BigDecimal.valueOf(amount), null);
        assertThat(res.breached()).isEqualTo(used + reserved + amount > limit);
        assertThat(res.remainingAmount()).isEqualByComparingTo(BigDecimal.valueOf(Math.max(0, limit - used - reserved - amount)));
        assertThat(res.remainingAmount().signum()).isGreaterThanOrEqualTo(0);
    }

    @Property(tries = 200)
    void countArithmetic(@ForAll @IntRange(min = 0, max = 50) int used, @ForAll @IntRange(min = 0, max = 50) int reserved,
                         @ForAll @IntRange(min = 0, max = 100) int max) {
        Rule r = rule("N", "GLOBAL", null, null, "DAILY", "COUNT", null, max, "REJECT", 1, false);
        RuleResult res = LimitEngine.evaluate(r, new Limit(null, max, null),
                new Usage(BigDecimal.ZERO, BigDecimal.ZERO, used, reserved), BigDecimal.ONE, null);
        assertThat(res.breached()).isEqualTo(used + reserved + 1 > max);
        assertThat(res.remainingCount()).isEqualTo(Math.max(0, max - used - reserved - 1));
        assertThat(res.remainingAmount()).isNull();
    }

    @Test
    void perTransactionBalanceAndOverrides() {
        Rule perTx = rule("P", "GLOBAL", null, null, "PER_TRANSACTION", "AMOUNT", "500", null, "REVIEW", 1, false);
        assertThat(LimitEngine.evaluate(perTx, new Limit(new BigDecimal("500"), null, null), Usage.ZERO,
                new BigDecimal("500.00"), null).breached()).isFalse();
        assertThat(LimitEngine.evaluate(perTx, new Limit(new BigDecimal("500"), null, null), Usage.ZERO,
                new BigDecimal("500.01"), null).breached()).isTrue();
        Rule bal = rule("B", "WALLET", "x", null, "PER_TRANSACTION", "BALANCE", "1000", null, "REJECT", 1, false);
        RuleResult b = LimitEngine.evaluate(bal, new Limit(new BigDecimal("1000"), null, null), Usage.ZERO,
                new BigDecimal("300"), new BigDecimal("800"));
        assertThat(b.breached()).isTrue();
        assertThat(b.remainingAmount()).isEqualByComparingTo("0");

        UUID ov = UUID.randomUUID();
        assertThat(LimitEngine.limit(perTx, new BigDecimal("900"), null, ov).maxAmount()).isEqualByComparingTo("900");
        Rule reg = rule("R", "GLOBAL", null, null, "DAILY", "AMOUNT", "100", null, "REJECT", 1, true);
        assertThat(LimitEngine.limit(reg, new BigDecimal("900"), null, ov).maxAmount()).isEqualByComparingTo("100");
    }

    @Test
    void strictestActionAndAppetite() {
        Rule review = rule("A", "GLOBAL", null, null, "DAILY", "AMOUNT", "1", null, "REVIEW", 1, false);
        Rule alert = rule("B", "GLOBAL", null, null, "DAILY", "AMOUNT", "1", null, "ALLOW_AND_ALERT", 1, false);
        Rule reject = rule("C", "GLOBAL", null, null, "DAILY", "AMOUNT", "1", null, "REJECT", 1, false);
        RuleResult r1 = new RuleResult(review, true, null, null, null, null, Usage.ZERO, null);
        RuleResult r2 = new RuleResult(alert, true, null, null, null, null, Usage.ZERO, null);
        RuleResult r3 = new RuleResult(reject, false, null, null, null, null, Usage.ZERO, null);
        assertThat(LimitEngine.strictest(List.of(r2, r1, r3))).isEqualTo("REVIEW");
        assertThat(LimitEngine.allowed("ALLOW_AND_ALERT")).isTrue();
        assertThat(LimitEngine.allowed("REVIEW")).isFalse();
        assertThat(LimitEngine.strictest(List.of(r3))).isNull();

        assertThat(Appetite.status(new BigDecimal("4"), new BigDecimal("5"), new BigDecimal("10"))).isEqualTo("GREEN");
        assertThat(Appetite.status(new BigDecimal("7"), new BigDecimal("5"), new BigDecimal("10"))).isEqualTo("AMBER");
        assertThat(Appetite.status(new BigDecimal("10"), new BigDecimal("5"), new BigDecimal("10"))).isEqualTo("RED");
        // lower is worse (a buffer): amber 15, red 10
        assertThat(Appetite.status(new BigDecimal("12"), new BigDecimal("15"), new BigDecimal("10"))).isEqualTo("AMBER");
        assertThat(Appetite.status(new BigDecimal("9"), new BigDecimal("15"), new BigDecimal("10"))).isEqualTo("RED");
        assertThat(Appetite.worsened("GREEN", "AMBER")).isTrue();
        assertThat(Appetite.worsened("RED", "AMBER")).isFalse();
    }
}
