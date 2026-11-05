package io.meridian.reconciliation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.reconciliation.domain.SafeguardingPolicy.Assessment;
import io.meridian.reconciliation.domain.SafeguardingPolicy.Component;
import io.meridian.reconciliation.domain.SafeguardingPolicy.RunDecision;
import io.meridian.reconciliation.domain.SafeguardingPolicy.Severity;
import io.meridian.reconciliation.domain.SafeguardingPolicy.Status;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure safeguarding stock-reconciliation policy: A - (B + C + D + E), classification, ageing and idempotency. */
class SafeguardingPolicyTest {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    /** Wallet-Edition default liability set B..E. */
    private static List<Component> liabilities(String wallet, String suspense, String unclaimed, String agentFloat) {
        return List.of(
                new Component("WALLET_LIABILITY", "2700/IND04", new BigDecimal(wallet)),
                new Component("EMONEY_SUSPENSE", "2710/IND02", new BigDecimal(suspense)),
                new Component("EMONEY_UNCLAIMED", "2720/IND06", new BigDecimal(unclaimed)),
                new Component("AGENT_FLOAT", "agent-float", new BigDecimal(agentFloat)));
    }

    @Test
    void exactZeroIsBalancedAndFullyBacked() {
        // A = 1,000,000 ; B+C+D+E = 900,000 + 50,000 + 30,000 + 20,000 = 1,000,000 -> result 0
        Assessment a = SafeguardingPolicy.assess(new BigDecimal("1000000"),
                liabilities("900000", "50000", "30000", "20000"), ZERO, ZERO);

        assertThat(a.liabilityTotal()).isEqualByComparingTo("1000000");
        assertThat(a.difference()).isEqualByComparingTo("0");
        assertThat(a.status()).isEqualTo(Status.BALANCED);
        assertThat(a.backed()).isTrue();
        assertThat(a.alarm()).isFalse();
        assertThat(a.exceptionRequired()).isFalse();
        assertThat(a.severity()).isEqualTo(Severity.NONE);
    }

    @Test
    void withinToleranceIsBalancedEitherSign() {
        // tolerance 1.00 absorbs rounding on both sides
        BigDecimal tol = new BigDecimal("1.00");
        Assessment surplus = SafeguardingPolicy.assess(new BigDecimal("1000000.75"),
                liabilities("1000000", "0", "0", "0"), tol, new BigDecimal("500"));
        Assessment deficit = SafeguardingPolicy.assess(new BigDecimal("999999.50"),
                liabilities("1000000", "0", "0", "0"), tol, new BigDecimal("500"));

        assertThat(surplus.status()).isEqualTo(Status.BALANCED);
        assertThat(deficit.status()).as("a rounding-sized negative is de-minimis, not a critical shortfall")
                .isEqualTo(Status.BALANCED);
        assertThat(deficit.backed()).isTrue();
        assertThat(deficit.alarm()).isFalse();
    }

    @Test
    void negativeBeyondToleranceIsCriticalShortfall() {
        // A short of liabilities by 5,000 -> unbacked -> CRITICAL regulator-reportable alarm + exception
        Assessment a = SafeguardingPolicy.assess(new BigDecimal("995000"),
                liabilities("900000", "50000", "30000", "20000"), new BigDecimal("1.00"), new BigDecimal("1000"));

        assertThat(a.difference()).isEqualByComparingTo("-5000");
        assertThat(a.status()).isEqualTo(Status.SHORTFALL);
        assertThat(a.backed()).isFalse();
        assertThat(a.alarm()).as("shortfall raises the alarm").isTrue();
        assertThat(a.exceptionRequired()).as("shortfall also opens a high-priority break").isTrue();
        assertThat(a.severity()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void negativeInFlightStillAlarms() {
        // even flagged as having a prior (in-flight) break, a negative result is always a reportable shortfall
        Assessment a = SafeguardingPolicy.assess(new BigDecimal("990000"),
                liabilities("1000000", "0", "0", "0"), new BigDecimal("1.00"), new BigDecimal("1000000"), true);
        assertThat(a.status()).isEqualTo(Status.SHORTFALL);
        assertThat(a.alarm()).isTrue();
    }

    @Test
    void smallSurplusWithinTimingBandIsTimingBreak() {
        // surplus 250 beyond tolerance 1.00 but within timing band 1,000 -> held, auto-clear expected, no escalation
        Assessment a = SafeguardingPolicy.assess(new BigDecimal("1000250"),
                liabilities("1000000", "0", "0", "0"), new BigDecimal("1.00"), new BigDecimal("1000"));

        assertThat(a.difference()).isEqualByComparingTo("250");
        assertThat(a.status()).isEqualTo(Status.TIMING);
        assertThat(a.backed()).isTrue();
        assertThat(a.alarm()).isFalse();
        assertThat(a.exceptionRequired()).as("a timing break is held, not escalated").isFalse();
        assertThat(a.severity()).isEqualTo(Severity.LOW);
    }

    @Test
    void largeSurplusBeyondTimingBandIsAmountBreak() {
        // surplus 5,000 beyond the timing band 1,000 -> persistent/unexplained -> escalate to the queue with ageing
        Assessment a = SafeguardingPolicy.assess(new BigDecimal("1005000"),
                liabilities("1000000", "0", "0", "0"), new BigDecimal("1.00"), new BigDecimal("1000"));

        assertThat(a.status()).isEqualTo(Status.AMOUNT);
        assertThat(a.alarm()).isFalse();
        assertThat(a.exceptionRequired()).isTrue();
        assertThat(a.severity()).isEqualTo(Severity.HIGH);
    }

    @Test
    void persistentSmallSurplusEscalatesFromTimingToAmount() {
        // same 250 surplus that would be TIMING, but it did not clear from a prior cycle -> AMOUNT
        Assessment a = SafeguardingPolicy.assess(new BigDecimal("1000250"),
                liabilities("1000000", "0", "0", "0"), new BigDecimal("1.00"), new BigDecimal("1000"), true);
        assertThat(a.status()).isEqualTo(Status.AMOUNT);
        assertThat(a.exceptionRequired()).isTrue();
    }

    @Test
    void ageingBucketsMatchReconMetrics() {
        assertThat(SafeguardingPolicy.ageingBucket(-3)).isEqualTo("0-1");
        assertThat(SafeguardingPolicy.ageingBucket(0)).isEqualTo("0-1");
        assertThat(SafeguardingPolicy.ageingBucket(1)).isEqualTo("0-1");
        assertThat(SafeguardingPolicy.ageingBucket(2)).isEqualTo("2-7");
        assertThat(SafeguardingPolicy.ageingBucket(7)).isEqualTo("2-7");
        assertThat(SafeguardingPolicy.ageingBucket(8)).isEqualTo("8-30");
        assertThat(SafeguardingPolicy.ageingBucket(30)).isEqualTo("8-30");
        assertThat(SafeguardingPolicy.ageingBucket(31)).isEqualTo("31+");
        assertThat(SafeguardingPolicy.ageingBucket(400)).isEqualTo("31+");
    }

    @Test
    void idempotentRerunDecisionIsForwardOnly() {
        // no record yet -> create
        assertThat(SafeguardingPolicy.decide(false, null, Status.BALANCED)).isEqualTo(RunDecision.CREATE);
        // same outcome -> return the existing record (idempotent, no duplicate)
        assertThat(SafeguardingPolicy.decide(true, Status.BALANCED, Status.BALANCED))
                .isEqualTo(RunDecision.RETURN_EXISTING);
        // more severe recomputation -> advance in place
        assertThat(SafeguardingPolicy.decide(true, Status.TIMING, Status.SHORTFALL)).isEqualTo(RunDecision.ADVANCE);
        assertThat(SafeguardingPolicy.decide(true, Status.BALANCED, Status.AMOUNT)).isEqualTo(RunDecision.ADVANCE);
        // less severe recomputation never regresses the audited status within the day
        assertThat(SafeguardingPolicy.decide(true, Status.SHORTFALL, Status.BALANCED))
                .isEqualTo(RunDecision.RETURN_EXISTING);
        assertThat(SafeguardingPolicy.decide(true, Status.AMOUNT, Status.TIMING))
                .isEqualTo(RunDecision.RETURN_EXISTING);
    }

    @Test
    void rejectsNegativeTolerance() {
        assertThatThrownBy(() -> SafeguardingPolicy.assess(new BigDecimal("1"),
                liabilities("1", "0", "0", "0"), new BigDecimal("-1"), ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void totalLiabilitiesSumsComponentsAndEmptyIsZero() {
        assertThat(SafeguardingPolicy.totalLiabilities(liabilities("900000", "50000", "30000", "20000")))
                .isEqualByComparingTo("1000000");
        assertThat(SafeguardingPolicy.totalLiabilities(List.of())).isEqualByComparingTo("0");
    }
}
