package io.meridian.rewards.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TierEvaluatorTest {
    static final TierEvaluator.Tier BLUE = new TierEvaluator.Tier(UUID.randomUUID(), 1, null, null);
    static final TierEvaluator.Tier SILVER = new TierEvaluator.Tier(UUID.randomUUID(), 2, new BigDecimal("1000"), 5000L);
    static final TierEvaluator.Tier GOLD = new TierEvaluator.Tier(UUID.randomUUID(), 3, new BigDecimal("5000"), 20000L);
    static final List<TierEvaluator.Tier> TIERS = List.of(GOLD, BLUE, SILVER);
    static final LocalDate TODAY = LocalDate.of(2026, 10, 6);

    @Test
    void newMemberGetsBaseTier() {
        var d = TierEvaluator.evaluate(TIERS, BigDecimal.ZERO, 0, null, null, TODAY, 12);
        assertThat(d.tierId()).isEqualTo(BLUE.id());
        assertThat(d.changed()).isTrue();
        assertThat(d.qualifiedUntil()).isEqualTo(TODAY.plusMonths(12));
    }

    @Test
    void eitherThresholdQualifiesAndUpgradesImmediately() {
        var bySpend = TierEvaluator.evaluate(TIERS, new BigDecimal("1000"), 0, BLUE.id(), TODAY.plusMonths(6), TODAY, 12);
        assertThat(bySpend.tierId()).isEqualTo(SILVER.id());
        assertThat(bySpend.changed()).isTrue();
        var byPoints = TierEvaluator.evaluate(TIERS, BigDecimal.ZERO, 20000, SILVER.id(), TODAY.plusMonths(6), TODAY, 12);
        assertThat(byPoints.tierId()).isEqualTo(GOLD.id());
    }

    @Test
    void downgradeWaitsForTheRequalificationDate() {
        var kept = TierEvaluator.evaluate(TIERS, BigDecimal.ZERO, 0, GOLD.id(), TODAY.plusDays(1), TODAY, 12);
        assertThat(kept.tierId()).isEqualTo(GOLD.id());
        assertThat(kept.changed()).isFalse();
        assertThat(kept.qualifiedUntil()).isEqualTo(TODAY.plusDays(1));
        var landed = TierEvaluator.evaluate(TIERS, new BigDecimal("1200"), 0, GOLD.id(), TODAY, TODAY, 12);
        assertThat(landed.tierId()).isEqualTo(SILVER.id());
        assertThat(landed.changed()).isTrue();
    }

    @Test
    void requalifyingExtendsTheTier() {
        var d = TierEvaluator.evaluate(TIERS, new BigDecimal("6000"), 0, GOLD.id(), TODAY.plusDays(3), TODAY, 12);
        assertThat(d.tierId()).isEqualTo(GOLD.id());
        assertThat(d.changed()).isFalse();
        assertThat(d.qualifiedUntil()).isEqualTo(TODAY.plusMonths(12));
    }
}
