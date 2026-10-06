package io.meridian.commission.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CommissionEngineTest {
    static final UUID TERRITORY = UUID.randomUUID();
    static final UUID BILLER = UUID.randomUUID();

    static CommissionEngine.Rule rule(String id, String beneficiary, String basis, String flat, String pct, String base) {
        return new CommissionEngine.Rule(uuid(id), 100, "CASH_IN", null, null, null, null, null, null, null, basis,
                dec(flat), dec(pct), null, null, base, beneficiary, null, BigDecimal.ONE, null, true);
    }

    static UUID uuid(String n) {
        return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", Integer.parseInt(n)));
    }

    static BigDecimal dec(String s) {
        return s == null ? null : new BigDecimal(s);
    }

    static CommissionEngine.Txn txn(String amount, String fee) {
        return new CommissionEngine.Txn("CASH_IN", "AGENT_APP", 1, "AGENT", TERRITORY, null, dec(amount), dec(fee));
    }

    @Test
    void flatPercentageAndCombinedOnAmountOrFee() {
        assertThat(CommissionEngine.compute(rule("1", "AGENT", "FLAT", "12.5", null, "AMOUNT"), txn("1000", "20")).commission())
                .isEqualByComparingTo("12.50");
        assertThat(CommissionEngine.compute(rule("1", "AGENT", "PERCENTAGE", null, "0.005", "AMOUNT"), txn("1000", "20")).commission())
                .isEqualByComparingTo("5.00");
        assertThat(CommissionEngine.compute(rule("1", "AGENT", "PERCENTAGE", null, "0.4", "FEE"), txn("1000", "20")).commission())
                .isEqualByComparingTo("8.00");
        CommissionEngine.Amounts a = CommissionEngine.compute(rule("1", "AGENT", "FLAT_PLUS_PERCENTAGE", "1", "0.001", "AMOUNT"),
                txn("2500", "0"));
        assertThat(a.commission()).isEqualByComparingTo("3.50");
        assertThat(a.base()).isEqualByComparingTo("2500");
    }

    @Test
    void halfEvenRounding() {
        // 0.125 -> 0.12 (half-even), 0.135 -> 0.14
        assertThat(CommissionEngine.compute(rule("1", "AGENT", "PERCENTAGE", null, "0.001", "AMOUNT"), txn("125", "0")).commission())
                .isEqualByComparingTo("0.12");
        assertThat(CommissionEngine.compute(rule("1", "AGENT", "PERCENTAGE", null, "0.001", "AMOUNT"), txn("135", "0")).commission())
                .isEqualByComparingTo("0.14");
    }

    @Test
    void tieredUsesTheBandOfTheBase() {
        List<CommissionEngine.Tier> tiers = List.of(
                new CommissionEngine.Tier(dec("0"), dec("999.99"), dec("10"), null),
                new CommissionEngine.Tier(dec("1000"), dec("4999.99"), dec("15"), dec("0.001")),
                new CommissionEngine.Tier(dec("5000"), null, null, dec("0.004")));
        CommissionEngine.Rule r = new CommissionEngine.Rule(uuid("1"), 100, "CASH_IN", null, null, null, null, null, null, null,
                "TIERED", null, null, tiers, null, "AMOUNT", "AGENT", null, BigDecimal.ONE, null, true);
        CommissionEngine.validate(r);
        assertThat(CommissionEngine.compute(r, txn("500", "0")).commission()).isEqualByComparingTo("10.00");
        assertThat(CommissionEngine.compute(r, txn("2000", "0")).commission()).isEqualByComparingTo("17.00");
        assertThat(CommissionEngine.compute(r, txn("10000", "0")).commission()).isEqualByComparingTo("40.00");
    }

    @Test
    void capShareAndWithholdingTax() {
        CommissionEngine.Rule r = new CommissionEngine.Rule(uuid("1"), 100, "CASH_IN", null, null, null, null, null, null, null,
                "PERCENTAGE", null, dec("0.01"), null, dec("50"), "AMOUNT", "AGENT", null, dec("0.6"), dec("0.10"), true);
        CommissionEngine.Amounts a = CommissionEngine.compute(r, txn("10000", "0")); // 100 -> cap 50 -> x0.6 = 30
        assertThat(a.commission()).isEqualByComparingTo("30.00");
        assertThat(a.tax()).isEqualByComparingTo("3.00");
        assertThat(a.net()).isEqualByComparingTo("27.00");
    }

    @Test
    void mostSpecificRuleWinsPerBeneficiaryThenPriority() {
        CommissionEngine.Rule generic = rule("1", "AGENT", "FLAT", "1", null, "AMOUNT");
        CommissionEngine.Rule byLevel = new CommissionEngine.Rule(uuid("2"), 100, "CASH_IN", null, null, "AGENT", null, null,
                null, null, "FLAT", dec("2"), null, null, null, "AMOUNT", "AGENT", null, BigDecimal.ONE, null, true);
        CommissionEngine.Rule byLevelAndTerritory = new CommissionEngine.Rule(uuid("3"), 200, "CASH_IN", null, null, "AGENT",
                TERRITORY, null, null, null, "FLAT", dec("3"), null, null, null, "AMOUNT", "AGENT", null, BigDecimal.ONE, null, true);
        CommissionEngine.Rule otherTerritory = new CommissionEngine.Rule(uuid("4"), 1, "CASH_IN", null, null, "AGENT",
                UUID.randomUUID(), null, null, null, "FLAT", dec("9"), null, null, null, "AMOUNT", "AGENT", null, BigDecimal.ONE, null, true);
        CommissionEngine.Rule superAgent = rule("5", "SUPER_AGENT", "FLAT", "0.5", null, "AMOUNT");
        CommissionEngine.Rule inactive = new CommissionEngine.Rule(uuid("6"), 1, "CASH_IN", "AGENT_APP", 1, "AGENT", TERRITORY,
                null, null, null, "FLAT", dec("99"), null, null, null, "AMOUNT", "AGENT", null, BigDecimal.ONE, null, false);
        Map<String, CommissionEngine.Rule> sel = CommissionEngine.select(
                List.of(generic, byLevel, byLevelAndTerritory, otherTerritory, superAgent, inactive), txn("100", "5"));
        assertThat(sel.get("AGENT").id()).isEqualTo(uuid("3"));
        assertThat(sel.get("SUPER_AGENT").id()).isEqualTo(uuid("5"));

        CommissionEngine.Rule samePrio = new CommissionEngine.Rule(uuid("7"), 50, "CASH_IN", null, null, null, null, null, null,
                null, "FLAT", dec("4"), null, null, null, "AMOUNT", "AGENT", null, BigDecimal.ONE, null, true);
        assertThat(CommissionEngine.select(List.of(generic, samePrio), txn("100", "5")).get("AGENT").id()).isEqualTo(uuid("7"));
    }

    @Test
    void amountBandAndBillerAndTxnTypeFilter() {
        CommissionEngine.Rule band = new CommissionEngine.Rule(uuid("1"), 100, "CASH_IN", null, null, null, null, null,
                dec("1000"), dec("5000"), "FLAT", dec("5"), null, null, null, "AMOUNT", "AGENT", null, BigDecimal.ONE, null, true);
        assertThat(CommissionEngine.matches(band, txn("999.99", "0"))).isFalse();
        assertThat(CommissionEngine.matches(band, txn("1000", "0"))).isTrue();
        assertThat(CommissionEngine.matches(band, txn("5000.01", "0"))).isFalse();
        CommissionEngine.Rule biller = new CommissionEngine.Rule(uuid("2"), 100, "BILL_PAYMENT", null, null, null, null, BILLER,
                null, null, "FLAT", dec("5"), null, null, null, "AMOUNT", "AGENT", null, BigDecimal.ONE, null, true);
        assertThat(CommissionEngine.matches(biller, txn("100", "0"))).isFalse();
        assertThat(CommissionEngine.matches(biller,
                new CommissionEngine.Txn("BILL_PAYMENT", "APP", 0, null, null, BILLER, dec("100"), dec("1")))).isTrue();
        assertThat(CommissionEngine.evaluate(List.of(rule("3", "AGENT", "PERCENTAGE", null, "0.1", "FEE")), txn("100", "0"))).isEmpty();
    }

    @Test
    void validationRejectsBrokenDefinitions() {
        assertThatThrownBy(() -> CommissionEngine.validate(rule("1", "AGENT", "FLAT", null, null, "AMOUNT")))
                .isInstanceOf(ApiException.class).hasMessageContaining("flat_amount");
        assertThatThrownBy(() -> CommissionEngine.validate(rule("1", "AGENT", "FLAT", "1", null, "TOTAL")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> CommissionEngine.validate(rule("1", "PLATFORM", "FLAT", "1", null, "AMOUNT")))
                .hasMessageContaining("beneficiary_wallet_id");
        List<CommissionEngine.Tier> overlap = List.of(new CommissionEngine.Tier(dec("0"), dec("100"), dec("1"), null),
                new CommissionEngine.Tier(dec("50"), null, dec("2"), null));
        assertThatThrownBy(() -> CommissionEngine.validate(new CommissionEngine.Rule(uuid("1"), 100, "CASH_IN", null, null, null,
                null, null, null, null, "TIERED", null, null, overlap, null, "AMOUNT", "AGENT", null, BigDecimal.ONE, null, true)))
                .hasMessageContaining("overlap");
        assertThatThrownBy(() -> CommissionEngine.validate(new CommissionEngine.Rule(uuid("1"), 100, "CASH_IN", null, null, null,
                null, null, null, null, "FLAT", dec("1"), null, null, null, "AMOUNT", "AGENT", null, dec("1.5"), null, true)))
                .hasMessageContaining("share");
    }

    @Test
    void payoutDueDates() {
        LocalDate wed = LocalDate.of(2026, 10, 7);
        assertThat(CommissionEngine.payoutDueOn("DAILY", wed)).isEqualTo(wed);
        assertThat(CommissionEngine.payoutDueOn("REALTIME", wed)).isEqualTo(wed);
        assertThat(CommissionEngine.payoutDueOn("WEEKLY", wed)).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(CommissionEngine.payoutDueOn("WEEKLY", LocalDate.of(2026, 10, 12))).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(CommissionEngine.payoutDueOn("MONTHLY", wed)).isEqualTo(LocalDate.of(2026, 11, 1));
    }
}
