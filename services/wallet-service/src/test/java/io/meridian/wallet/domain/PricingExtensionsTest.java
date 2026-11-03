package io.meridian.wallet.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** WE-PRICE-1 fee-engine extensions: telescopic / cumulative charging (PE-7), milestone (PE-18), onsite/offsite (PE-16). */
class PricingExtensionsTest {

    static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    static final List<FeeCalculator.Tier> TIERS = List.of(
            new FeeCalculator.Tier(d("0"), d("1000"), d("0"), d("0.02")),       // 2% on the first 1,000
            new FeeCalculator.Tier(d("1000"), d("5000"), d("5"), d("0.01")),    // flat 5 + 1% on 1,000..5,000
            new FeeCalculator.Tier(d("5000"), null, d("10"), d("0.005")));      // flat 10 + 0.5% above 5,000

    /** A TIERED rule with the given tier_mode / charge_scope (other dimensions empty). */
    static FeeCalculator.Rule tiered(String tierMode, String scope) {
        return new FeeCalculator.Rule(UUID.randomUUID(), "t", "P2P", null, null, null, null, null, "TIERED", null, null,
                TIERS, null, null, null, 100, tierMode, scope, null, null, null, null, null, null, null);
    }

    @Test
    void bandDefaultReproducesTodaysCharge() {
        // the legacy (band) constructor and the explicit BAND rule must compute identically
        FeeCalculator.Rule legacy = new FeeCalculator.Rule(UUID.randomUUID(), "t", "P2P", null, null, null, null, null,
                "TIERED", null, null, TIERS, null, null, null, 100);
        FeeCalculator.Rule band = tiered("BAND", "INSTRUMENT");
        for (String amt : List.of("500", "2000", "8000")) {
            assertThat(FeeCalculator.compute(band, d(amt))).isEqualByComparingTo(FeeCalculator.compute(legacy, d(amt)));
        }
        // band charges the whole amount in the containing band: 2000 -> 5 + 1% * 2000 = 25
        assertThat(FeeCalculator.compute(band, d("2000"))).isEqualByComparingTo("25.00");
    }

    @Test
    void telescopicSumsPerSliceCharges() {
        FeeCalculator.Rule tele = tiered("TELESCOPIC", "INSTRUMENT");
        // 2000: slice1 2% * 1000 = 20 ; slice2 flat 5 + 1% * 1000 = 15 => 35.00
        assertThat(FeeCalculator.compute(tele, d("2000"))).isEqualByComparingTo("35.00");
        // 500: only slice1 reached: 2% * 500 = 10.00
        assertThat(FeeCalculator.compute(tele, d("500"))).isEqualByComparingTo("10.00");
        // 6000: slice1 20 + slice2 (5 + 1%*4000=45) + slice3 (10 + 0.5%*1000=15) = 80.00
        assertThat(FeeCalculator.compute(tele, d("6000"))).isEqualByComparingTo("80.00");
    }

    @Test
    void cumulativeChargesTheMarginalDelta() {
        FeeCalculator.Rule tele = tiered("TELESCOPIC", "CUMULATIVE");
        // with 1,000 already transacted this month, a 1,000 txn charges f(2000) - f(1000)
        BigDecimal f2000 = FeeCalculator.telescopic(TIERS, d("2000"));
        BigDecimal f1000 = FeeCalculator.telescopic(TIERS, d("1000"));
        assertThat(FeeCalculator.compute(tele, d("1000"), d("1000")))
                .isEqualByComparingTo(f2000.subtract(f1000).setScale(2, java.math.RoundingMode.HALF_EVEN));
        // from zero cumulative, cumulative == instrument
        assertThat(FeeCalculator.compute(tele, d("2000"), BigDecimal.ZERO))
                .isEqualByComparingTo(FeeCalculator.compute(tiered("TELESCOPIC", "INSTRUMENT"), d("2000")));
    }

    @Test
    void milestoneAppliesOnlyOnTheNthTransactionInTheWindow() {
        // a rule that only applies on the 3rd..5th monthly transaction (+1 specificity beats a generic rule)
        FeeCalculator.Rule milestone = new FeeCalculator.Rule(UUID.randomUUID(), "m", "P2P", null, null, null, null, null,
                "FLAT", d("1"), null, null, null, null, null, 100, "BAND", "INSTRUMENT", null, null, 3, 5, "MONTHLY",
                null, null);
        // context monthly count-before = 2 -> this is the 3rd -> matches
        FeeCalculator.Context third = ctx(2);
        FeeCalculator.Context first = ctx(0);
        FeeCalculator.Context sixth = ctx(5);
        assertThat(FeeCalculator.matches(milestone, third)).isTrue();
        assertThat(FeeCalculator.matches(milestone, first)).isFalse();
        assertThat(FeeCalculator.matches(milestone, sixth)).isFalse();
        assertThat(FeeCalculator.specificity(milestone)).isEqualTo(1);
    }

    @Test
    void remoteTxnTypeNarrowsTheRule() {
        FeeCalculator.Rule offsite = new FeeCalculator.Rule(UUID.randomUUID(), "o", "CASH_IN", null, null, null, null,
                null, "FLAT", d("2"), null, null, null, null, null, 100, "BAND", "INSTRUMENT", null, null, null, null,
                null, null, "OFFSITE");
        FeeCalculator.Context onsiteCtx = new FeeCalculator.Context("CASH_IN", "AGENT_APP", "CUSTOMER", 1, d("100"),
                "ONSITE", BigDecimal.ZERO, null, null, null, null);
        FeeCalculator.Context offsiteCtx = new FeeCalculator.Context("CASH_IN", "AGENT_APP", "CUSTOMER", 1, d("100"),
                "OFFSITE", BigDecimal.ZERO, null, null, null, null);
        assertThat(FeeCalculator.matches(offsite, onsiteCtx)).isFalse();
        assertThat(FeeCalculator.matches(offsite, offsiteCtx)).isTrue();
        assertThat(FeeCalculator.specificity(offsite)).isEqualTo(1);
    }

    @Test
    void onsiteOffsiteClassification() {
        assertThat(RemoteClassifier.classify("C1", "L1", "C1", "L1")).isEqualTo("ONSITE");
        assertThat(RemoteClassifier.classify("C2", "L1", "C1", "L1")).isEqualTo("OFFSITE");
        assertThat(RemoteClassifier.classify(null, null, "C1", "L1")).isEqualTo("NOT_CLASSIFIED");
        assertThat(RemoteClassifier.classify("C1", "L1", null, null)).isEqualTo("NOT_CLASSIFIED");
        // outlet geofenced by LAC only
        assertThat(RemoteClassifier.classify(null, "L1", null, "L1")).isEqualTo("ONSITE");
        assertThat(RemoteClassifier.classify(null, "L2", null, "L1")).isEqualTo("OFFSITE");
    }

    static FeeCalculator.Context ctx(int monthlyCountBefore) {
        return new FeeCalculator.Context("P2P", "APP", "CUSTOMER", 1, d("100"), null, BigDecimal.ZERO, 0, 0,
                monthlyCountBefore, 0);
    }
}
