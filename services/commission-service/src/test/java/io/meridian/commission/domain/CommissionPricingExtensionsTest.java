package io.meridian.commission.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** WE-PRICE-1 commission-engine extensions: telescopic (PE-7), milestone (PE-18), inactive (PE-13), sharing (PE-14). */
class CommissionPricingExtensionsTest {

    static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    static final List<CommissionEngine.Tier> TIERS = List.of(
            new CommissionEngine.Tier(d("0"), d("1000"), d("0"), d("0.02")),
            new CommissionEngine.Tier(d("1000"), d("5000"), d("5"), d("0.01")),
            new CommissionEngine.Tier(d("5000"), null, d("10"), d("0.005")));

    static CommissionEngine.Txn amount(String a) {
        return new CommissionEngine.Txn("CASH_IN", "AGENT_APP", null, "AGENT", null, null, d(a), BigDecimal.ZERO);
    }

    /** Full-constructor TIERED rule with explicit WE-PRICE-1 fields; base = AMOUNT, beneficiary AGENT. */
    static CommissionEngine.Rule tiered(String tierMode, String scope) {
        return new CommissionEngine.Rule(UUID.randomUUID(), 100, "CASH_IN", null, null, null, null, null, null, null,
                "TIERED", null, null, TIERS, null, "AMOUNT", "AGENT", null, BigDecimal.ONE, null, true,
                tierMode, scope, null, null, null, null, null, null, null, false, null, null, null, false);
    }

    @Test
    void bandDefaultMatchesLegacyConstructor() {
        CommissionEngine.Rule legacy = new CommissionEngine.Rule(UUID.randomUUID(), 100, "CASH_IN", null, null, null, null,
                null, null, null, "TIERED", null, null, TIERS, null, "AMOUNT", "AGENT", null, BigDecimal.ONE, null, true);
        CommissionEngine.Rule band = tiered("BAND", "INSTRUMENT");
        for (String a : List.of("500", "2000", "8000")) {
            assertThat(CommissionEngine.compute(band, amount(a)).commission())
                    .isEqualByComparingTo(CommissionEngine.compute(legacy, amount(a)).commission());
        }
        assertThat(CommissionEngine.compute(band, amount("2000")).commission()).isEqualByComparingTo("25.00");
    }

    @Test
    void telescopicProgression() {
        CommissionEngine.Rule tele = tiered("TELESCOPIC", "INSTRUMENT");
        assertThat(CommissionEngine.compute(tele, amount("2000")).commission()).isEqualByComparingTo("35.00");
        assertThat(CommissionEngine.compute(tele, amount("6000")).commission()).isEqualByComparingTo("80.00");
    }

    @Test
    void cumulativeChargesMarginalDelta() {
        CommissionEngine.Rule tele = tiered("TELESCOPIC", "CUMULATIVE");
        CommissionEngine.Txn t = new CommissionEngine.Txn("CASH_IN", "AGENT_APP", null, "AGENT", null, null, d("1000"),
                BigDecimal.ZERO, null, d("1000"), null, null, null, null, null);
        BigDecimal expected = CommissionEngine.telescopic(TIERS, d("2000"))
                .subtract(CommissionEngine.telescopic(TIERS, d("1000")));
        assertThat(CommissionEngine.compute(tele, t).commission())
                .isEqualByComparingTo(expected.setScale(2, java.math.RoundingMode.HALF_EVEN));
    }

    @Test
    void milestoneNthInWindow() {
        CommissionEngine.Rule rule = new CommissionEngine.Rule(UUID.randomUUID(), 100, "CASH_IN", null, null, null, null,
                null, null, null, "FLAT", d("1"), null, null, null, "AMOUNT", "AGENT", null, BigDecimal.ONE, null, true,
                "BAND", "INSTRUMENT", null, null, 3, 3, "MONTHLY", null, null, false, null, null, null, false);
        assertThat(CommissionEngine.matches(rule, count(2))).isTrue();   // 3rd
        assertThat(CommissionEngine.matches(rule, count(1))).isFalse();  // 2nd
        assertThat(CommissionEngine.matches(rule, count(3))).isFalse();  // 4th
        assertThat(CommissionEngine.specificity(rule)).isEqualTo(1);
    }

    @Test
    void inactiveCustomerGate() {
        CommissionEngine.Rule rule = new CommissionEngine.Rule(UUID.randomUUID(), 100, "CASH_IN", null, null, null, null,
                null, null, null, "FLAT", d("5"), null, null, null, "AMOUNT", "AGENT", null, BigDecimal.ONE, null, true,
                "BAND", "INSTRUMENT", null, null, null, null, null, null, null, true, 30, "IMMEDIATE", null, false);
        assertThat(CommissionEngine.matches(rule, inactive(null))).isFalse();
        assertThat(CommissionEngine.matches(rule, inactive(10))).isFalse();
        assertThat(CommissionEngine.matches(rule, inactive(45))).isTrue();
        assertThat(CommissionEngine.specificity(rule)).isEqualTo(1);
    }

    @Test
    void sharingSplitSumsToTheWholeCommission() {
        // Merchant -> Aggregator -> Service-Provider split: shares 0.5 / 0.3 / 0.2 of 1% of 10,000 = 100 total
        CommissionEngine.Rule merchant = share("MERCHANT", "0.5");
        CommissionEngine.Rule aggregator = share("AGGREGATOR", "0.3");
        CommissionEngine.Rule sp = share("SERVICE_PROVIDER", "0.2");
        List<CommissionEngine.Selected> selected = CommissionEngine.evaluate(List.of(merchant, aggregator, sp), amount("10000"));
        BigDecimal total = selected.stream().map(s -> s.amounts().commission()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total).isEqualByComparingTo("100.00");
        assertThat(selected).hasSize(3);
        Map<String, BigDecimal> byRole = new java.util.HashMap<>();
        for (CommissionEngine.Selected s : selected) {
            byRole.put(s.rule().beneficiary(), s.amounts().commission());
        }
        assertThat(byRole.get("MERCHANT")).isEqualByComparingTo("50.00");
        assertThat(byRole.get("AGGREGATOR")).isEqualByComparingTo("30.00");
        assertThat(byRole.get("SERVICE_PROVIDER")).isEqualByComparingTo("20.00");
    }

    @Test
    void cpcrJournalBalancesAndGroupIdIsDeterministic() {
        UUID receivable = UUID.randomUUID();
        UUID payable = UUID.randomUUID();
        List<ShareLedger.Line> lines = ShareLedger.cpcr(receivable, payable, d("100.00"));
        assertThat(ShareLedger.balanced(lines)).isTrue();
        assertThat(lines).hasSize(2);
        UUID txn = UUID.fromString("00000000-0000-0000-0000-000000000abc");
        assertThat(ShareLedger.shareGroupId(txn)).isEqualTo(ShareLedger.shareGroupId(txn));
    }

    static CommissionEngine.Rule share(String beneficiary, String shareFraction) {
        UUID wallet = "MERCHANT".equals(beneficiary) ? UUID.randomUUID() : UUID.randomUUID();
        return new CommissionEngine.Rule(UUID.randomUUID(), 100, "CASH_IN", null, null, null, null, null, null, null,
                "PERCENTAGE", null, d("0.01"), null, null, "AMOUNT", beneficiary, wallet, d(shareFraction), null, true,
                "BAND", "INSTRUMENT", null, null, null, null, null, null, null, false, null, null, null, true);
    }

    static CommissionEngine.Txn count(int monthlyBefore) {
        return new CommissionEngine.Txn("CASH_IN", "AGENT_APP", null, "AGENT", null, null, d("100"), BigDecimal.ZERO,
                null, BigDecimal.ZERO, 0, 0, monthlyBefore, 0, null);
    }

    static CommissionEngine.Txn inactive(Integer days) {
        return new CommissionEngine.Txn("CASH_IN", "AGENT_APP", null, "AGENT", null, null, d("100"), BigDecimal.ZERO,
                null, BigDecimal.ZERO, null, null, null, null, days);
    }
}
