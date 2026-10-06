package io.meridian.cardclearing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.cardclearing.domain.ClearingMatcher.AuthView;
import io.meridian.cardclearing.domain.ClearingMatcher.Kind;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.Scale;
import org.junit.jupiter.api.Test;

class ClearingMatcherTest {
    static final BigDecimal TOL = new BigDecimal("0.20");

    static AuthView auth(String status, String ccy, String approved, String cleared, String reversed) {
        return new AuthView(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, "AUTHORIZATION", status,
                ccy, new BigDecimal(approved), new BigDecimal(approved), new BigDecimal(cleared),
                new BigDecimal(reversed), UUID.randomUUID(), UUID.randomUUID(), null);
    }

    @Property
    void withinToleranceMatchesAboveIsException(
            @ForAll @BigRange(min = "0.01", max = "100000") @Scale(2) BigDecimal approved,
            @ForAll @BigRange(min = "0.01", max = "2.00") @Scale(4) BigDecimal factor) {
        AuthView a = auth("APPROVED", "USD", approved.toPlainString(), "0", "0");
        BigDecimal cleared = approved.multiply(factor).setScale(2, java.math.RoundingMode.HALF_EVEN);
        if (cleared.signum() <= 0) {
            return;
        }
        Kind k = ClearingMatcher.evaluate(a, cleared, "USD", TOL).kind();
        boolean within = cleared.compareTo(approved.multiply(BigDecimal.ONE.add(TOL))) <= 0;
        assertThat(k).isEqualTo(within ? Kind.MATCHED : Kind.EXCEPTION);
    }

    @Property
    void neverMatchesADifferentCurrency(@ForAll @BigRange(min = "0.01", max = "1000") @Scale(2) BigDecimal amount,
                                        @ForAll("currencies") String other) {
        AuthView a = auth("APPROVED", "USD", "1000", "0", "0");
        assertThat(ClearingMatcher.evaluate(a, amount, other, TOL).kind()).isEqualTo(Kind.EXCEPTION);
    }

    @net.jqwik.api.Provide
    net.jqwik.api.Arbitrary<String> currencies() {
        return net.jqwik.api.Arbitraries.of("EUR", "GBP", "JPY", "CHF");
    }

    @Test
    void remainingAccountsForClearedAndReversed() {
        AuthView a = auth("PARTIALLY_CLEARED", "USD", "100", "30", "20");
        assertThat(a.remaining()).isEqualByComparingTo("50");
        assertThat(ClearingMatcher.evaluate(a, new BigDecimal("60"), "USD", TOL).kind()).isEqualTo(Kind.MATCHED);
        assertThat(ClearingMatcher.evaluate(a, new BigDecimal("60.01"), "USD", TOL).kind()).isEqualTo(Kind.EXCEPTION);
    }

    @Test
    void statusRules() {
        assertThat(ClearingMatcher.evaluate(auth("EXPIRED", "USD", "10", "0", "0"), BigDecimal.TEN, "USD", TOL).kind())
                .isEqualTo(Kind.HOLD_GONE);
        assertThat(ClearingMatcher.evaluate(auth("REVERSED", "USD", "10", "0", "10"), BigDecimal.TEN, "USD", TOL).kind())
                .isEqualTo(Kind.HOLD_GONE);
        assertThat(ClearingMatcher.evaluate(auth("CLEARED", "USD", "10", "10", "0"), BigDecimal.TEN, "USD", TOL).reason())
                .contains("CLEARED");
        assertThat(ClearingMatcher.evaluate(auth("DECLINED", "USD", "10", "0", "0"), BigDecimal.TEN, "USD", TOL).kind())
                .isEqualTo(Kind.EXCEPTION);
    }

    @Test
    void selectPrefersOriginalMatchableAuthorisation() {
        AuthView declined = auth("DECLINED", "USD", "10", "0", "0");
        AuthView cleared = auth("CLEARED", "USD", "10", "10", "0");
        AuthView approved = auth("APPROVED", "USD", "10", "0", "0");
        AuthView incremental = new AuthView(UUID.randomUUID(), null, null, approved.id(), "INCREMENTAL", "APPROVED",
                "USD", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, null, null, null);
        assertThat(ClearingMatcher.select(List.of(incremental, declined, cleared, approved))).isEqualTo(approved);
        assertThat(ClearingMatcher.select(List.of(declined, cleared))).isEqualTo(cleared);
        assertThat(ClearingMatcher.select(List.of(declined))).isNull();
    }
}
