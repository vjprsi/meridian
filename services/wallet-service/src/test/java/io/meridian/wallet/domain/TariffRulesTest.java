package io.meridian.wallet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.common.error.ApiException;
import io.meridian.wallet.application.BankLinkService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Fee engine, tier limits, wallet numbers and name matching. */
class TariffRulesTest {

    static FeeCalculator.Rule rule(String code, String basis, String flat, String pct, String channel, Integer tier,
                                   String min, String max, String minFee, String maxFee, String tax, int priority,
                                   List<FeeCalculator.Tier> tiers) {
        return new FeeCalculator.Rule(UUID.randomUUID(), code, "P2P", channel, null, tier, d(min), d(max), basis, d(flat),
                d(pct), tiers, d(minFee), d(maxFee), d(tax), priority);
    }

    static BigDecimal d(String s) {
        return s == null ? null : new BigDecimal(s);
    }

    static FeeCalculator.Context ctx(String amount, String channel, Integer tier) {
        return new FeeCalculator.Context("P2P", channel, "CUSTOMER", tier, new BigDecimal(amount));
    }

    @Test
    void computesEachBasisWithRoundingAndClamps() {
        assertThat(FeeCalculator.compute(rule("a", "FLAT", "5", null, null, null, null, null, null, null, null, 100, null),
                new BigDecimal("999"))).isEqualByComparingTo("5.00");
        assertThat(FeeCalculator.compute(rule("b", "PERCENTAGE", null, "0.015", null, null, null, null, null, null, null, 100, null),
                new BigDecimal("1000.30"))).isEqualByComparingTo("15.00"); // 15.0045 -> 15.00
        assertThat(FeeCalculator.compute(rule("c", "FLAT_PLUS_PERCENTAGE", "2", "0.01", null, null, null, null, null, null, null, 100, null),
                new BigDecimal("250"))).isEqualByComparingTo("4.50");
        // min and max fee
        assertThat(FeeCalculator.compute(rule("d", "PERCENTAGE", null, "0.01", null, null, null, null, "10", "50", null, 100, null),
                new BigDecimal("100"))).isEqualByComparingTo("10.00");
        assertThat(FeeCalculator.compute(rule("e", "PERCENTAGE", null, "0.01", null, null, null, null, "10", "50", null, 100, null),
                new BigDecimal("100000"))).isEqualByComparingTo("50.00");
        // half-even: 0.125 -> 0.12, 0.135 -> 0.14
        assertThat(FeeCalculator.compute(rule("f", "PERCENTAGE", null, "0.001", null, null, null, null, null, null, null, 100, null),
                new BigDecimal("125"))).isEqualByComparingTo("0.12");
        assertThat(FeeCalculator.compute(rule("g", "PERCENTAGE", null, "0.001", null, null, null, null, null, null, null, 100, null),
                new BigDecimal("135"))).isEqualByComparingTo("0.14");
    }

    @Test
    void tieredBandsApplyToTheWholeAmount() {
        List<FeeCalculator.Tier> tiers = List.of(
                new FeeCalculator.Tier(d("0"), d("100"), d("0"), null),
                new FeeCalculator.Tier(d("100.01"), d("1000"), d("10"), null),
                new FeeCalculator.Tier(d("1000.01"), null, d("10"), d("0.005")));
        FeeCalculator.Rule r = rule("t", "TIERED", null, null, null, null, null, null, null, null, null, 100, tiers);
        assertThat(FeeCalculator.compute(r, d("50"))).isEqualByComparingTo("0");
        assertThat(FeeCalculator.compute(r, d("500"))).isEqualByComparingTo("10");
        assertThat(FeeCalculator.compute(r, d("5000"))).isEqualByComparingTo("35");
        FeeCalculator.validate(r);
    }

    @Test
    void mostSpecificRuleWinsThenPriority() {
        FeeCalculator.Rule general = rule("general", "FLAT", "5", null, null, null, null, null, null, null, "0.10", 100, null);
        FeeCalculator.Rule ussd = rule("ussd", "FLAT", "3", null, "USSD", null, null, null, null, null, null, 100, null);
        FeeCalculator.Rule ussdTier0Low = rule("ussd-t0", "FLAT", "1", null, "USSD", 0, "0", "100", null, null, null, 100, null);
        FeeCalculator.Rule cheap = rule("cheap", "FLAT", "4", null, null, null, null, null, null, null, null, 10, null);
        List<FeeCalculator.Rule> rules = List.of(general, ussd, ussdTier0Low, cheap);
        assertThat(FeeCalculator.quote(rules, ctx("50", "USSD", 0)).fee()).isEqualByComparingTo("1");
        assertThat(FeeCalculator.quote(rules, ctx("500", "USSD", 0)).fee()).isEqualByComparingTo("3");
        // equally specific: the lower priority number wins
        FeeCalculator.Fee app = FeeCalculator.quote(rules, ctx("500", "APP", 1));
        assertThat(app.fee()).isEqualByComparingTo("4");
        assertThat(app.tax()).isEqualByComparingTo("0");
        FeeCalculator.Fee taxed = FeeCalculator.quote(List.of(general), ctx("500", "APP", 1));
        assertThat(taxed.tax()).isEqualByComparingTo("0.50");
        assertThat(taxed.total()).isEqualByComparingTo("5.50");
        assertThat(FeeCalculator.quote(List.of(), ctx("500", "APP", 1))).isEqualTo(FeeCalculator.Fee.NONE);
    }

    @Test
    void invalidRuleDefinitionsAreRejected() {
        assertThatThrownBy(() -> FeeCalculator.validate(rule("x", "PERCENTAGE", null, null, null, null, null, null, null, null, null, 1, null)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> FeeCalculator.validate(rule("x", "FLAT", "-1", null, null, null, null, null, null, null, null, 1, null)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> FeeCalculator.validate(rule("x", "TIERED", null, null, null, null, null, null, null, null, null, 1,
                List.of(new FeeCalculator.Tier(d("0"), d("100"), d("1"), null), new FeeCalculator.Tier(d("50"), null, d("2"), null)))))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> FeeCalculator.validate(rule("x", "FLAT", "1", null, null, null, "10", "5", null, null, null, 1, null)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void tierLimitsCheckPerTransactionDailyMonthlyAndBalance() {
        LimitEvaluator.Limit l = new LimitEvaluator.Limit(UUID.randomUUID(), null, d("10"), d("1000"), d("5000"),
                d("2000"), 5, d("10000"), 50);
        LimitEvaluator.Usage u = new LimitEvaluator.Usage(d("1500"), 4, d("9000"), 10);
        assertThat(LimitEvaluator.checkDebit(l, u, d("5"))).get().extracting(LimitEvaluator.Violation::code)
                .isEqualTo("amount_below_minimum");
        assertThat(LimitEvaluator.checkDebit(l, u, d("1001"))).isPresent();
        assertThat(LimitEvaluator.checkDebit(l, u, d("600"))).get().extracting(LimitEvaluator.Violation::message)
                .asString().contains("Daily limit");
        assertThat(LimitEvaluator.checkDebit(l, u, d("500"))).isEmpty();
        assertThat(LimitEvaluator.checkDebit(l, new LimitEvaluator.Usage(d("0"), 5, d("0"), 5), d("50"))).isPresent();
        assertThat(LimitEvaluator.checkDebit(l, new LimitEvaluator.Usage(d("0"), 0, d("9990"), 0), d("50"))).get()
                .extracting(LimitEvaluator.Violation::message).asString().contains("Monthly limit");
        assertThat(LimitEvaluator.checkBalance(l, d("4900"), d("101"))).get().extracting(LimitEvaluator.Violation::code)
                .isEqualTo("max_balance_exceeded");
        assertThat(LimitEvaluator.checkBalance(l, d("4900"), d("100"))).isEmpty();
        assertThat(LimitEvaluator.remaining(d("2000"), d("2500"))).isEqualByComparingTo("0");
        assertThat(LimitEvaluator.remaining(null, d("1"))).isNull();
    }

    @Test
    void walletNumbersCarryALuhnDigitAndReferencesAreUnambiguous() {
        for (int i = 0; i < 50; i++) {
            String n = WalletNumbers.generate("26");
            assertThat(n).hasSize(12).startsWith("26");
            assertThat(WalletNumbers.isValid(n)).isTrue();
            String broken = n.substring(0, 11) + ((n.charAt(11) - '0' + 1) % 10);
            assertThat(WalletNumbers.isValid(broken)).isFalse();
        }
        assertThat(WalletNumbers.luhnDigit("7992739871")).isEqualTo(3);
        assertThat(WalletNumbers.reference()).matches("WT[A-HJ-NP-Z2-9]{10}");
    }

    @Test
    void namesMatchIgnoringCaseAccentsAndOrder() {
        assertThat(BankLinkService.namesMatch("WANJIRU, Alice", "Alice Wanjiru")).isTrue();
        assertThat(BankLinkService.namesMatch("José Álvarez", "jose alvarez")).isTrue();
        assertThat(BankLinkService.namesMatch("Alice M. Wanjiru", "Alice Wanjiru")).isTrue();
        assertThat(BankLinkService.namesMatch("Bob Otieno", "Alice Wanjiru")).isFalse();
        assertThat(BankLinkService.namesMatch("", "Alice")).isFalse();
    }

    @Test
    void walletStatusMachine() {
        WalletLifecycle.requireWalletTransition("PENDING", "ACTIVE");
        WalletLifecycle.requireWalletTransition("ACTIVE", "SUSPENDED");
        WalletLifecycle.requireWalletTransition("SUSPENDED", "ACTIVE");
        assertThatThrownBy(() -> WalletLifecycle.requireWalletTransition("CLOSED", "ACTIVE")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> WalletLifecycle.requireWalletTransition("BLOCKED", "CLOSED")).isInstanceOf(ApiException.class);
        assertThat(WalletLifecycle.ledgerStatus("SUSPENDED")).isEqualTo("FROZEN_DEBIT");
        assertThat(WalletLifecycle.ledgerStatus("BLOCKED")).isEqualTo("FROZEN");
        assertThat(WalletLifecycle.canDebit("DORMANT")).isFalse();
        assertThat(WalletLifecycle.canCredit("DORMANT")).isTrue();
        WalletLifecycle.requireTxnTransition("PENDING_CONFIRMATION", "EXPIRED");
        assertThatThrownBy(() -> WalletLifecycle.requireTxnTransition("FAILED", "COMPLETED")).isInstanceOf(ApiException.class);
    }
}
