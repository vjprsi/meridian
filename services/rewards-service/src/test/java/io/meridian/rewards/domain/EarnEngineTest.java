package io.meridian.rewards.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EarnEngineTest {
    static final OffsetDateTime SAT_NOON = OffsetDateTime.parse("2026-10-03T12:00:00Z"); // Saturday
    static final OffsetDateTime MON_NOON = OffsetDateTime.parse("2026-10-05T12:00:00Z"); // Monday

    static EarnEngine.Rule rule(String name, int priority, boolean stackable, BigDecimal ppu, BigDecimal rate) {
        return new EarnEngine.Rule(UUID.randomUUID(), name, "CARD_SPEND", priority, stackable, List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), null, ppu, rate, null, BigDecimal.ONE, null, null, List.of(),
                null, null, ZoneId.of("UTC"), OffsetDateTime.parse("2026-01-01T00:00:00Z"), null);
    }

    static EarnEngine.Txn txn(String amount, String mcc, OffsetDateTime at) {
        return new EarnEngine.Txn("CARD_SPEND", new BigDecimal(amount), mcc, "M1", "GB", "POS", null, at);
    }

    @Test
    void basePointsWithTierMultiplierAreFloored() {
        var r = EarnEngine.evaluate("POINTS", List.of(rule("base", 100, true, new BigDecimal("1"), null)),
                txn("10.99", "5411", MON_NOON), new BigDecimal("1.5"), null);
        assertThat(r.points()).isEqualTo(16); // floor(10.99 * 1.5)
        assertThat(r.cashback()).isEqualByComparingTo("0");
    }

    @Test
    void cashbackRoundsHalfEvenAndRespectsPeriodCap() {
        var base = rule("cb", 100, true, null, new BigDecimal("0.05"));
        var capped = new EarnEngine.Rule(base.id(), base.name(), base.trigger(), 100, true, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), null, null, new BigDecimal("0.05"), null, BigDecimal.ONE,
                new BigDecimal("3.00"), "MONTH", List.of(), null, null, ZoneId.of("UTC"), base.validFrom(), null);
        var r = EarnEngine.evaluate("CASHBACK", List.of(capped), txn("100.10", null, MON_NOON), BigDecimal.ONE,
                (rule, from, to) -> {
                    assertThat(from.toString()).isEqualTo("2026-10-01T00:00:00Z");
                    assertThat(to.toString()).isEqualTo("2026-11-01T00:00:00Z");
                    return new EarnEngine.Used(0, new BigDecimal("1.50"));
                });
        assertThat(r.cashback()).isEqualByComparingTo("1.50");
        assertThat(r.awards().getFirst().capped()).isTrue();
        var uncapped = EarnEngine.evaluate("CASHBACK", List.of(base), txn("100.10", null, MON_NOON), BigDecimal.ONE, null);
        assertThat(uncapped.cashback()).isEqualByComparingTo("5.00"); // 5.005 -> 5.00 HALF_EVEN
    }

    @Test
    void mccIncludeExcludeAndMinimumAmount() {
        var r = rule("groceries", 100, true, new BigDecimal("3"), null);
        var g = new EarnEngine.Rule(r.id(), r.name(), r.trigger(), 100, true, List.of("5411"), List.of("7995"), List.of(),
                List.of(), List.of("POS"), List.of("GB"), new BigDecimal("5"), new BigDecimal("3"), null, null, BigDecimal.ONE,
                null, null, List.of(), null, null, ZoneId.of("UTC"), r.validFrom(), null);
        assertThat(EarnEngine.mismatch(g, txn("10", "5411", MON_NOON))).isNull();
        assertThat(EarnEngine.mismatch(g, txn("10", "5999", MON_NOON))).isEqualTo("mcc_not_included");
        assertThat(EarnEngine.mismatch(g, txn("4.99", "5411", MON_NOON))).isEqualTo("below_min_amount");
        var ex = new EarnEngine.Rule(r.id(), r.name(), r.trigger(), 100, true, List.of(), List.of("7995"), List.of(),
                List.of(), List.of(), List.of(), null, BigDecimal.ONE, null, null, BigDecimal.ONE, null, null, List.of(),
                null, null, ZoneId.of("UTC"), r.validFrom(), null);
        assertThat(EarnEngine.mismatch(ex, txn("10", "7995", MON_NOON))).isEqualTo("mcc_excluded");
        assertThat(EarnEngine.mismatch(ex, new EarnEngine.Txn("WALLET_PAYMENT", BigDecimal.TEN, null, null, null, null, null, MON_NOON)))
                .isEqualTo("trigger");
    }

    @Test
    void weekendBoostAndWrappingTimeWindowInRuleZone() {
        var r = rule("weekend", 10, true, new BigDecimal("2"), null);
        var weekend = new EarnEngine.Rule(r.id(), r.name(), r.trigger(), 10, true, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null, new BigDecimal("2"), null, null, BigDecimal.ONE, null, null, List.of(6, 7),
                "22:00", "02:00", ZoneId.of("Europe/London"), r.validFrom(), null);
        // 2026-10-03 22:30 London (BST, UTC+1) = 21:30Z Saturday
        assertThat(EarnEngine.mismatch(weekend, txn("10", null, OffsetDateTime.parse("2026-10-03T21:30:00Z")))).isNull();
        assertThat(EarnEngine.mismatch(weekend, txn("10", null, SAT_NOON))).isEqualTo("time_window");
        assertThat(EarnEngine.mismatch(weekend, txn("10", null, OffsetDateTime.parse("2026-10-05T23:30:00Z")))).isEqualTo("day_of_week");
        assertThat(EarnEngine.inWindow(LocalTime.parse("01:59"), "22:00", "02:00")).isTrue();
        assertThat(EarnEngine.inWindow(LocalTime.parse("02:00"), "22:00", "02:00")).isFalse();
        assertThat(EarnEngine.inWindow(LocalTime.parse("09:00"), "08:00", "18:00")).isTrue();
    }

    @Test
    void nonStackableWinnerIsAppliedAloneAndStackablesCombine() {
        var promo = rule("promo", 10, false, new BigDecimal("5"), null);
        var base = rule("base", 100, true, BigDecimal.ONE, null);
        var bonus = rule("bonus", 50, true, BigDecimal.ONE, null);
        var alone = EarnEngine.evaluate("POINTS", List.of(base, promo, bonus), txn("10", null, MON_NOON), BigDecimal.ONE, null);
        assertThat(alone.awards()).hasSize(1);
        assertThat(alone.points()).isEqualTo(50);
        var lowPriorityExclusive = rule("exclusive", 200, false, new BigDecimal("9"), null);
        var stacked = EarnEngine.evaluate("POINTS", List.of(base, bonus, lowPriorityExclusive), txn("10", null, MON_NOON),
                BigDecimal.ONE, null);
        assertThat(stacked.awards()).hasSize(2);
        assertThat(stacked.points()).isEqualTo(20);
    }

    @Test
    void validityAndTransactionCapAndHybrid() {
        var r = rule("x", 1, true, new BigDecimal("2"), new BigDecimal("0.01"));
        var limited = new EarnEngine.Rule(r.id(), r.name(), r.trigger(), 1, true, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null, new BigDecimal("2"), new BigDecimal("0.01"), null, new BigDecimal("2"),
                new BigDecimal("50"), "TRANSACTION", List.of(), null, null, ZoneId.of("UTC"), r.validFrom(),
                OffsetDateTime.parse("2026-12-31T00:00:00Z"));
        var hybrid = EarnEngine.evaluate("HYBRID", List.of(limited), txn("100", null, MON_NOON), BigDecimal.ONE, null);
        // points 100*2*2=400 (uncapped: cap applies to cash for a cashback-rate rule), cash 100*0.01*2=2.00
        assertThat(hybrid.points()).isEqualTo(400);
        assertThat(hybrid.cashback()).isEqualByComparingTo("2.00");
        var pointsOnly = EarnEngine.evaluate("POINTS", List.of(limited), txn("100", null, MON_NOON), BigDecimal.ONE, null);
        assertThat(pointsOnly.points()).isEqualTo(50);
        assertThat(EarnEngine.mismatch(limited, txn("1", null, OffsetDateTime.parse("2027-01-01T00:00:00Z")))).isEqualTo("expired");
        assertThat(EarnEngine.mismatch(limited, txn("1", null, OffsetDateTime.parse("2025-01-01T00:00:00Z")))).isEqualTo("not_yet_valid");
    }

    @Test
    void capWindows() {
        var at = OffsetDateTime.parse("2026-08-15T23:30:00Z");
        assertThat(EarnEngine.capWindow("DAY", ZoneId.of("Europe/London"), at)[0].toString()).isEqualTo("2026-08-15T23:00:00Z");
        assertThat(EarnEngine.capWindow("WEEK", ZoneId.of("UTC"), at)[0].toString()).isEqualTo("2026-08-10T00:00:00Z");
        assertThat(EarnEngine.capWindow("QUARTER", ZoneId.of("UTC"), at)[0].toString()).isEqualTo("2026-07-01T00:00:00Z");
        assertThat(EarnEngine.capWindow("YEAR", ZoneId.of("UTC"), at)[1].toString()).isEqualTo("2027-01-01T00:00:00Z");
    }
}
