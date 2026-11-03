package io.meridian.agentnetwork.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.agentnetwork.domain.StockRules.Account;
import io.meridian.agentnetwork.domain.StockRules.Leg;
import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** WE-FLOAT-1 pure rules: posting-leg selection, sweep math, run-status aggregation, unclaimed aging/balancing. */
class StockRulesTest {

    // ------------------------------------------------------------------ stock-op leg selection + validation

    @Test
    void stockCreationDebitsBankClearingAndCreditsCapital() {
        List<Leg> legs = StockRules.legs("STOCK_CREATION");
        assertThat(legs).containsExactly(new Leg(Account.BANK_CLEARING, Account.CAPITAL));
    }

    @Test
    void reimbursementDebitsEntityToCapitalThenCapitalToBankClearing() {
        assertThat(StockRules.legs("REIMBURSEMENT")).containsExactly(
                new Leg(Account.ENTITY, Account.CAPITAL),
                new Leg(Account.CAPITAL, Account.BANK_CLEARING));
        assertThatThrownBy(() -> StockRules.legs("NONSENSE"))
                .extracting(e -> ((ApiException) e).code()).isEqualTo("invalid_operation_type");
    }

    @Test
    void entityWalletRequiredForReimbursementOnly() {
        StockRules.requireEntity("REIMBURSEMENT", "w1");
        StockRules.requireEntity("STOCK_CREATION", null);
        assertThatThrownBy(() -> StockRules.requireEntity("REIMBURSEMENT", null))
                .extracting(e -> ((ApiException) e).code()).isEqualTo("entity_wallet_required");
        assertThatThrownBy(() -> StockRules.requireEntity("STOCK_CREATION", "w1"))
                .extracting(e -> ((ApiException) e).code()).isEqualTo("entity_wallet_not_allowed");
    }

    @Test
    void amountAndInstrumentValidation() {
        StockRules.requireAmount(new BigDecimal("10.00"));
        StockRules.requireInstrument("CHEQUE");
        assertThatThrownBy(() -> StockRules.requireAmount(new BigDecimal("0")))
                .extracting(e -> ((ApiException) e).code()).isEqualTo("invalid_amount");
        assertThatThrownBy(() -> StockRules.requireAmount(new BigDecimal("1.234")))
                .extracting(e -> ((ApiException) e).code()).isEqualTo("invalid_amount");
        assertThatThrownBy(() -> StockRules.requireInstrument("GOLD"))
                .extracting(e -> ((ApiException) e).code()).isEqualTo("invalid_instrument");
    }

    // ------------------------------------------------------------------ auto-deallocation sweep math

    @Test
    void sweepableIsEverythingAboveTheResidual() {
        assertThat(StockRules.sweepable(new BigDecimal("100"), new BigDecimal("30"))).isEqualByComparingTo("70");
        assertThat(StockRules.sweepable(new BigDecimal("30"), new BigDecimal("30"))).isEqualByComparingTo("0");
        assertThat(StockRules.sweepable(new BigDecimal("20"), new BigDecimal("30"))).isEqualByComparingTo("0");
        assertThat(StockRules.sweepable(new BigDecimal("100"), null)).isEqualByComparingTo("100");
        assertThat(StockRules.isCandidate(new BigDecimal("100"), new BigDecimal("30"))).isTrue();
        assertThat(StockRules.isCandidate(new BigDecimal("30"), new BigDecimal("30"))).isFalse();
    }

    @Test
    void effectiveResidualPrecedenceRunThenFloatThenConfig() {
        assertThat(StockRules.effectiveResidual(new BigDecimal("5"), new BigDecimal("10"), new BigDecimal("20"))).isEqualByComparingTo("5");
        assertThat(StockRules.effectiveResidual(null, new BigDecimal("10"), new BigDecimal("20"))).isEqualByComparingTo("10");
        assertThat(StockRules.effectiveResidual(null, null, new BigDecimal("20"))).isEqualByComparingTo("20");
        assertThat(StockRules.effectiveResidual(null, null, null)).isEqualByComparingTo("0");
    }

    // ------------------------------------------------------------------ run-status aggregation

    @Test
    void runStatusAggregation() {
        assertThat(StockRules.runStatus(0, 0, 0)).isEqualTo("SUCCEEDED");   // nothing to do
        assertThat(StockRules.runStatus(3, 3, 0)).isEqualTo("SUCCEEDED");
        assertThat(StockRules.runStatus(3, 2, 1)).isEqualTo("PARTIALLY_SUCCEEDED");
        assertThat(StockRules.runStatus(3, 0, 3)).isEqualTo("FAILED");
    }

    // ------------------------------------------------------------------ unclaimed settlement

    @Test
    void finalStageAndAccountFollowSettleTo() {
        assertThat(StockRules.finalStage("CAPITAL")).isEqualTo("UNCLAIMED_TO_CAPITAL");
        assertThat(StockRules.finalStage("income")).isEqualTo("UNCLAIMED_TO_INCOME");
        assertThat(StockRules.finalAccount("CAPITAL")).isEqualTo(Account.CAPITAL);
        assertThat(StockRules.finalAccount("INCOME")).isEqualTo(Account.INCOME);
        assertThat(StockRules.finalAccount(null)).isEqualTo(Account.CAPITAL);
    }

    @Test
    void journalMustBalance() {
        StockRules.requireBalanced(new BigDecimal("50.00"), new BigDecimal("50.0"));
        assertThatThrownBy(() -> StockRules.requireBalanced(new BigDecimal("50"), new BigDecimal("40")))
                .extracting(e -> ((ApiException) e).code()).isEqualTo("unbalanced_journal");
    }
}
