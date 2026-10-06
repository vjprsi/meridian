package io.meridian.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.Scale;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void addsAndSubtractsWithoutFloatingPointError() {
        Money a = Money.of("0.10", "USD");
        Money b = Money.of("0.20", "USD");
        assertThat(a.plus(b)).isEqualTo(Money.of("0.3", "USD"));
        assertThat(a.plus(b).minus(b)).isEqualTo(a);
    }

    @Test
    void rejectsMixedCurrencies() {
        assertThatThrownBy(() -> Money.of("1", "USD").plus(Money.of("1", "EUR")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void roundsToCurrencyMinorUnitBankersRounding() {
        assertThat(Money.of("10.005", "USD").roundToMinorUnit(RoundingMode.HALF_EVEN).amount())
                .isEqualByComparingTo("10.00");
        assertThat(Money.of("10.015", "USD").roundToMinorUnit(RoundingMode.HALF_EVEN).amount())
                .isEqualByComparingTo("10.02");
        assertThat(Money.of("1234.5", "JPY").roundToMinorUnit(RoundingMode.HALF_EVEN).amount())
                .isEqualByComparingTo("1234");
    }

    @Test
    void rejectsInvalidCurrencyAndExcessScale() {
        assertThatThrownBy(() -> Money.of("1", "usd")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of("1.123456789", "USD")).isInstanceOf(IllegalArgumentException.class);
    }

    @Property(tries = 500)
    void additionIsCommutativeAndReversible(
            @ForAll @BigRange(min = "-1000000000", max = "1000000000") @Scale(8) BigDecimal x,
            @ForAll @BigRange(min = "-1000000000", max = "1000000000") @Scale(8) BigDecimal y) {
        Money a = new Money(x, "EUR");
        Money b = new Money(y, "EUR");
        assertThat(a.plus(b)).isEqualTo(b.plus(a));
        assertThat(a.plus(b).minus(b)).isEqualTo(a);
    }
}
