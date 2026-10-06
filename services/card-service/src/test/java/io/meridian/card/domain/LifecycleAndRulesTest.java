package io.meridian.card.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class LifecycleAndRulesTest {

    private static void conflict(Runnable r) {
        assertThatThrownBy(r::run).isInstanceOf(ApiException.class).extracting("code").isEqualTo("invalid_transition");
    }

    @Test
    void cardTransitions() {
        assertThat(CardLifecycle.initialStatus("VIRTUAL")).isEqualTo("ACTIVE");
        assertThat(CardLifecycle.initialStatus("PHYSICAL")).isEqualTo("CREATED");
        assertThat(CardLifecycle.activate("CREATED")).isEqualTo("ACTIVE");
        conflict(() -> CardLifecycle.activate("ACTIVE"));
        assertThat(CardLifecycle.freeze("ACTIVE")).isEqualTo("FROZEN");
        conflict(() -> CardLifecycle.freeze("FROZEN"));
        conflict(() -> CardLifecycle.freeze("CREATED"));
        assertThat(CardLifecycle.unfreeze("FROZEN")).isEqualTo("ACTIVE");
        conflict(() -> CardLifecycle.unfreeze("ACTIVE"));
        assertThat(CardLifecycle.block("FROZEN", "STOLEN")).isEqualTo("STOLEN");
        conflict(() -> CardLifecycle.block("CLOSED", "LOST"));
        assertThatThrownBy(() -> CardLifecycle.block("ACTIVE", "FROZEN")).extracting("code").isEqualTo("invalid_block_status");
        assertThat(CardLifecycle.close("LOST")).isEqualTo("CLOSED");
        conflict(() -> CardLifecycle.close("CLOSED"));
        conflict(() -> CardLifecycle.close("REPLACED"));
        assertThat(CardLifecycle.expire("ACTIVE")).isEqualTo("EXPIRED");
        conflict(() -> CardLifecycle.expire("CLOSED"));
        conflict(() -> CardLifecycle.pinAllowed("BLOCKED"));
        CardLifecycle.pinAllowed("CREATED");
        conflict(() -> CardLifecycle.renew("CREATED"));
        conflict(() -> CardLifecycle.orderPhysicalAllowed("CLOSED"));
    }

    @Test
    void replacementStatusOfOldCard() {
        assertThat(CardLifecycle.replace("ACTIVE", "LOST")).isEqualTo("LOST");
        assertThat(CardLifecycle.replace("ACTIVE", "STOLEN")).isEqualTo("STOLEN");
        assertThat(CardLifecycle.replace("FROZEN", "FRAUD")).isEqualTo("BLOCKED");
        assertThat(CardLifecycle.replace("ACTIVE", "DAMAGED")).isEqualTo("REPLACED");
        assertThat(CardLifecycle.replace("LOST", "LOST")).isEqualTo("LOST");
        assertThat(CardLifecycle.replace("STOLEN", "DAMAGED")).isEqualTo("STOLEN");
        conflict(() -> CardLifecycle.replace("REPLACED", "DAMAGED"));
        conflict(() -> CardLifecycle.replace("CLOSED", "LOST"));
    }

    @Test
    void orderTransitions() {
        assertThat(OrderLifecycle.submit("PENDING")).isEqualTo("SENT_TO_BUREAU");
        conflict(() -> OrderLifecycle.submit("SHIPPED"));
        assertThat(OrderLifecycle.cancel("PENDING")).isEqualTo("CANCELLED");
        conflict(() -> OrderLifecycle.cancel("SENT_TO_BUREAU"));
        assertThat(OrderLifecycle.bureauUpdate("SENT_TO_BUREAU", "IN_PRODUCTION")).isEqualTo("IN_PRODUCTION");
        assertThat(OrderLifecycle.bureauUpdate("IN_PRODUCTION", "SHIPPED")).isEqualTo("SHIPPED");
        assertThat(OrderLifecycle.bureauUpdate("SHIPPED", "SHIPPED")).isNull();
        assertThat(OrderLifecycle.bureauUpdate("SHIPPED", "RETURNED")).isEqualTo("RETURNED");
        assertThat(OrderLifecycle.bureauUpdate("IN_PRODUCTION", "FAILED")).isEqualTo("FAILED");
        conflict(() -> OrderLifecycle.bureauUpdate("DELIVERED", "FAILED"));
        conflict(() -> OrderLifecycle.bureauUpdate("PENDING", "SHIPPED"));
        OrderLifecycle.reship("RETURNED");
        conflict(() -> OrderLifecycle.reship("DELIVERED"));
    }

    private static ControlRules.ControlSpec spec(String type, List<String> values) {
        return new ControlRules.ControlSpec(type, "ANY", values, null, null, null, null, null, null);
    }

    private static void invalid(ControlRules.ControlSpec s, String code) {
        assertThatThrownBy(() -> ControlRules.validateControl(s)).isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo(code);
    }

    @Test
    void controlValidation() {
        ControlRules.validateControl(spec("MCC_DENY", List.of("7995", "6051")));
        invalid(spec("MCC_DENY", List.of("799")), "invalid_control_values");
        invalid(spec("MCC_ALLOW", List.of()), "invalid_control_values");
        ControlRules.validateControl(spec("COUNTRY_DENY", List.of("RU")));
        invalid(spec("COUNTRY_DENY", List.of("rus")), "invalid_control_values");
        ControlRules.validateControl(spec("CHANNEL_DENY", List.of("ATM", "ECOM")));
        invalid(spec("CHANNEL_DENY", List.of("PHONE")), "invalid_control_values");
        invalid(spec("CURRENCY_ALLOW", List.of("usd")), "invalid_control_values");
        invalid(spec("BOGUS", List.of()), "invalid_control_type");
        invalid(spec("AMOUNT_MAX", null), "invalid_control_values");
        ControlRules.validateControl(new ControlRules.ControlSpec("AMOUNT_MAX", "ECOM", null, new BigDecimal("500"),
                "EUR", null, null, null, null));
        ControlRules.TimeWindow office = new ControlRules.TimeWindow(List.of("MON", "FRI"), "08:00", "18:30");
        ControlRules.validateControl(new ControlRules.ControlSpec("TIME_WINDOW", "ANY", null, null, null, office,
                "Europe/London", null, null));
        invalid(new ControlRules.ControlSpec("TIME_WINDOW", "ANY", null, null, null, office, "Mars/Base", null, null),
                "invalid_timezone");
        invalid(new ControlRules.ControlSpec("TIME_WINDOW", "ANY", null, null, null,
                new ControlRules.TimeWindow(List.of("MON"), "18:00", "08:00"), "UTC", null, null), "invalid_time_window");
        OffsetDateTime t = OffsetDateTime.parse("2026-10-01T00:00:00Z");
        invalid(new ControlRules.ControlSpec("MCC_DENY", "ANY", List.of("7995"), null, null, null, null, t, t),
                "invalid_validity");
        assertThatThrownBy(() -> ControlRules.validateScope(null, null)).extracting("code").isEqualTo("invalid_scope");
        assertThatThrownBy(() -> ControlRules.validateScope("a", "b")).extracting("code").isEqualTo("invalid_scope");
    }

    @Test
    void limitValidation() {
        ControlRules.validateLimit("DAILY", "ANY", new BigDecimal("1000"), 10, "EUR");
        assertThatThrownBy(() -> ControlRules.validateLimit("DAILY", "ANY", null, null, "EUR"))
                .extracting("code").isEqualTo("limit_required");
        assertThatThrownBy(() -> ControlRules.validateLimit("PER_TRANSACTION", "ANY", null, 3, "EUR"))
                .extracting("code").isEqualTo("invalid_count");
        assertThatThrownBy(() -> ControlRules.validateLimit("DAILY", "ANY", new BigDecimal("-1"), null, "EUR"))
                .extracting("code").isEqualTo("invalid_amount");
    }

    @Test
    void statementArithmetic() {
        StatementCalculator.Statement s = StatementCalculator.compute(new BigDecimal("100.00"), List.of(
                new StatementCalculator.Line("DEBIT", new BigDecimal("250.00"), "Coffee shop"),
                new StatementCalculator.Line("DEBIT", new BigDecimal("200.00"), "ATM withdrawal"),
                new StatementCalculator.Line("DEBIT", new BigDecimal("5.00"), "Card fee"),
                new StatementCalculator.Line("DEBIT", new BigDecimal("3.20"), "Interest charge"),
                new StatementCalculator.Line("CREDIT", new BigDecimal("150.00"), "Payment")));
        assertThat(s.purchases()).isEqualByComparingTo("250");
        assertThat(s.cashAdvances()).isEqualByComparingTo("200");
        assertThat(s.fees()).isEqualByComparingTo("5");
        assertThat(s.interest()).isEqualByComparingTo("3.20");
        assertThat(s.payments()).isEqualByComparingTo("150");
        assertThat(s.closing()).isEqualByComparingTo("408.20");
        assertThat(s.minimumDue()).isEqualByComparingTo("25.00");
        assertThat(StatementCalculator.minimumDue(new BigDecimal("2000"))).isEqualByComparingTo("100.00");
        assertThat(StatementCalculator.minimumDue(new BigDecimal("10"))).isEqualByComparingTo("10.00");
        assertThat(StatementCalculator.minimumDue(new BigDecimal("-5"))).isEqualByComparingTo("0");
    }
}
