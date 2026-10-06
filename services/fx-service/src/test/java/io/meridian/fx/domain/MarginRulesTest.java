package io.meridian.fx.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MarginRulesTest {
    static final LocalDate TODAY = LocalDate.parse("2026-10-06");

    static MarginRules.Rule rule(String pair, String segment, String channel, String min, String max, int bps, int priority,
                                 String from, String to) {
        return new MarginRules.Rule(UUID.randomUUID(), pair, segment, channel, min == null ? null : new BigDecimal(min),
                max == null ? null : new BigDecimal(max), bps, null, priority, from == null ? null : LocalDate.parse(from),
                to == null ? null : LocalDate.parse(to));
    }

    @Test
    void exactPairBeatsWildcardsEvenWithWorsePriority() {
        var any = rule("*/*", null, null, null, null, 100, 1, "2026-01-01", null);
        var toEur = rule("*/EUR", null, null, null, null, 80, 1, "2026-01-01", null);
        var exact = rule("USD/EUR", null, null, null, null, 40, 500, "2026-01-01", null);
        assertThat(MarginRules.select(List.of(any, toEur, exact), "USD", "EUR", null, null, BigDecimal.TEN, TODAY))
                .contains(exact);
        assertThat(MarginRules.select(List.of(any, toEur), "USD", "EUR", null, null, BigDecimal.TEN, TODAY)).contains(toEur);
        assertThat(MarginRules.select(List.of(any, toEur), "USD", "GBP", null, null, BigDecimal.TEN, TODAY)).contains(any);
    }

    @Test
    void lowestPriorityWinsAmongEquallySpecificRules() {
        var a = rule("USD/EUR", null, null, null, null, 40, 20, "2026-01-01", null);
        var b = rule("USD/EUR", null, null, null, null, 30, 10, "2026-01-01", null);
        assertThat(MarginRules.select(List.of(a, b), "USD", "EUR", null, null, BigDecimal.TEN, TODAY)).contains(b);
    }

    @Test
    void amountBandSegmentChannelAndValidityFilter() {
        var small = rule("USD/EUR", null, null, "0", "9999.99", 60, 10, "2026-01-01", null);
        var large = rule("USD/EUR", null, null, "10000", null, 20, 10, "2026-01-01", null);
        var premium = rule("USD/EUR", "PREMIUM", null, null, null, 10, 5, "2026-01-01", null);
        var mobile = rule("USD/EUR", null, "MOBILE", null, null, 15, 5, "2026-01-01", null);
        var expired = rule("USD/EUR", null, null, null, null, 1, 1, "2025-01-01", "2025-12-31");
        var future = rule("USD/EUR", null, null, null, null, 1, 1, "2027-01-01", null);
        List<MarginRules.Rule> all = List.of(small, large, premium, mobile, expired, future);
        assertThat(MarginRules.select(all, "USD", "EUR", null, "API", new BigDecimal("500"), TODAY)).contains(small);
        assertThat(MarginRules.select(all, "USD", "EUR", null, "API", new BigDecimal("50000"), TODAY)).contains(large);
        assertThat(MarginRules.select(all, "USD", "EUR", "PREMIUM", "API", new BigDecimal("500"), TODAY)).contains(premium);
        assertThat(MarginRules.select(all, "USD", "EUR", null, "MOBILE", new BigDecimal("500"), TODAY)).contains(mobile);
        assertThat(MarginRules.select(List.of(expired, future), "USD", "EUR", null, null, BigDecimal.ONE, TODAY)).isEmpty();
    }

    @Test
    void pairValidation() {
        assertThat(MarginRules.validPair("USD/EUR")).isTrue();
        assertThat(MarginRules.validPair("*/EUR")).isTrue();
        assertThat(MarginRules.validPair("*/*")).isTrue();
        assertThat(MarginRules.validPair("USDEUR")).isFalse();
        assertThat(MarginRules.validPair("XXQ/EUR")).isFalse();
    }
}
