package io.meridian.cardauthorization.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

class PeriodsFxIsoTest {

    @Test
    void periodStarts() {
        OffsetDateTime t = OffsetDateTime.parse("2026-10-08T15:30:00+02:00"); // Thursday, 13:30Z
        assertThat(Periods.start("DAILY", t)).isEqualTo(OffsetDateTime.parse("2026-10-08T00:00:00Z"));
        assertThat(Periods.start("WEEKLY", t)).isEqualTo(OffsetDateTime.parse("2026-10-05T00:00:00Z"));
        assertThat(Periods.start("MONTHLY", t)).isEqualTo(OffsetDateTime.parse("2026-10-01T00:00:00Z"));
        assertThat(Periods.start("YEARLY", t)).isEqualTo(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        assertThat(Periods.start("LIFETIME", t)).isEqualTo(Periods.EPOCH);
        assertThat(Periods.start("DAILY", OffsetDateTime.parse("2026-10-09T01:00:00+05:00")))
                .isEqualTo(OffsetDateTime.parse("2026-10-08T00:00:00Z")); // UTC day boundary
        assertThat(Periods.cumulative("PER_TRANSACTION")).isFalse();
    }

    @Property(tries = 300)
    void periodStartNeverAfterNow(@ForAll @LongRange(min = 0, max = 4_000_000_000L) long epochSecond,
                                  @ForAll("periods") String period) {
        OffsetDateTime t = OffsetDateTime.ofInstant(java.time.Instant.ofEpochSecond(epochSecond), java.time.ZoneOffset.UTC);
        assertThat(Periods.start(period, t)).isBeforeOrEqualTo(t);
    }

    @net.jqwik.api.Provide
    net.jqwik.api.Arbitrary<String> periods() {
        return net.jqwik.api.Arbitraries.of("DAILY", "WEEKLY", "MONTHLY", "YEARLY", "LIFETIME");
    }

    @Test
    void fxConversionWithMarkup() {
        Fx.Billing b = Fx.convert(new BigDecimal("100.00"), "EUR", "USD", new BigDecimal("1.0850"), new BigDecimal("0.02"));
        assertThat(b.markup()).isEqualByComparingTo("2.17");
        assertThat(b.amount()).isEqualByComparingTo("110.67");
        Fx.Billing same = Fx.convert(new BigDecimal("10.005"), "USD", "USD", null, new BigDecimal("0.02"));
        assertThat(same.amount()).isEqualByComparingTo("10.00"); // HALF_EVEN, no markup on same currency
        assertThat(same.markup()).isZero();
        Fx.Billing jpy = Fx.convert(new BigDecimal("10.00"), "USD", "JPY", new BigDecimal("149.555"), BigDecimal.ZERO);
        assertThat(jpy.amount()).isEqualByComparingTo("1496");
        assertThat(jpy.amount().scale()).isZero();
        assertThat(Fx.proportional(new BigDecimal("50.00"), new BigDecimal("100.00"), new BigDecimal("110.67"), "USD"))
                .isEqualByComparingTo("55.34");
    }

    @Property(tries = 300)
    void markupIsNeverNegativeAndBillingCoversConversion(@ForAll @IntRange(min = 1, max = 10_000_000) int cents,
                                                         @ForAll @IntRange(min = 1, max = 500) int markupBp) {
        BigDecimal amt = BigDecimal.valueOf(cents, 2);
        Fx.Billing b = Fx.convert(amt, "EUR", "USD", new BigDecimal("1.0850"), BigDecimal.valueOf(markupBp, 4));
        assertThat(b.markup()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(b.amount()).isGreaterThanOrEqualTo(amt.multiply(new BigDecimal("1.0850")).setScale(2, java.math.RoundingMode.HALF_EVEN));
        assertThat(b.amount().scale()).isEqualTo(2);
    }

    @Test
    void isoCodes() {
        assertThat(Iso.code("INSUFFICIENT_FUNDS")).isEqualTo("51");
        assertThat(Iso.code("CARD_EXPIRED")).isEqualTo("54");
        assertThat(Iso.code("FRAUD_SUSPECTED")).isEqualTo("59");
        assertThat(Iso.code("LIMIT_EXCEEDED")).isEqualTo("61");
        assertThat(Iso.code("VELOCITY_EXCEEDED")).isEqualTo("65");
        assertThat(Iso.code("CONTROL_MCC")).isEqualTo("57");
        assertThat(Iso.code("INVALID_CVV")).isEqualTo("N7");
        assertThat(Iso.code("PIN_TRIES_EXCEEDED")).isEqualTo("75");
        assertThat(Iso.code("JIT_TIMEOUT")).isEqualTo("91");
        assertThat(Iso.code("ORIGINAL_NOT_FOUND")).isEqualTo("25");
        assertThat(Iso.code("SOMETHING_NEW")).isEqualTo("05");
        assertThat(Iso.authCode()).matches("[A-Z0-9]{6}");
    }
}
