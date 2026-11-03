package io.meridian.limits.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.limits.domain.LimitModel.Rule;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** WE-LIMIT-1 unit tests for the TCP compiler, Rank-2 subset validation and the transfer-rule matrix. */
class TcpCompilerTest {
    private final JsonMapper json = JsonMapper.builder().build();

    private ObjectNode cumulative(String id) {
        ObjectNode p = json.createObjectNode();
        p.put("id", id);
        p.put("code", "CUM1");
        p.put("regulatory_profile_code", "KYC_T2");
        p.put("currency", "USD");
        p.put("direction", "DEBIT");
        p.put("daily_amount", "5000");
        p.put("daily_count", "20");
        p.put("monthly_amount", "50000");
        p.put("yearly_amount", "600000");
        p.put("yearly_enabled", false);
        p.put("max_balance", "10000");
        p.put("breach_action", "REJECT");
        p.put("priority", 100);
        p.put("is_regulatory", true);
        return p;
    }

    @Test
    void cumulativeCompilesToWindowedRegulatoryRulesWithDeterministicIds() {
        ObjectNode p = cumulative("11111111-1111-1111-1111-111111111111");
        List<Rule> rules = TcpCompiler.compileCumulative(p);
        // daily amount + daily count + monthly amount + balance; yearly disabled => excluded
        assertThat(rules).extracting(Rule::period).containsExactlyInAnyOrder("DAILY", "DAILY", "MONTHLY", "PER_TRANSACTION");
        assertThat(rules).allMatch(r -> "REGULATORY_PROFILE".equals(r.scope()));
        assertThat(rules).allMatch(r -> "KYC_T2".equals(r.scopeRef()));
        assertThat(rules).allMatch(Rule::regulatory);
        assertThat(rules).allMatch(r -> "DEBIT".equals(r.direction()));
        // Deterministic: recompiling yields identical ids (so counters survive a cache refresh).
        assertThat(TcpCompiler.compileCumulative(p)).extracting(Rule::id)
                .containsExactlyElementsOf(rules.stream().map(Rule::id).toList());
    }

    @Test
    void yearlyWindowOnlyWhenEnabled() {
        ObjectNode p = cumulative("22222222-2222-2222-2222-222222222222");
        p.put("yearly_enabled", true);
        assertThat(TcpCompiler.compileCumulative(p)).extracting(Rule::period).contains("YEARLY");
    }

    @Test
    void instrumentCompilesPerTransactionAndWindowedRules() {
        ObjectNode p = json.createObjectNode();
        p.put("id", "33333333-3333-3333-3333-333333333333");
        p.put("code", "ITC1");
        p.put("payment_instrument", "WALLET");
        p.put("grade_code", "GOLD");
        p.put("currency", "USD");
        p.put("per_txn_max", "1000");
        p.put("per_txn_min", "1");
        p.put("amount_multiple", "10");
        p.put("max_transfer_pct", "0.5");
        p.put("min_remaining_balance", "5");
        p.put("daily_amount", "4000");
        p.put("breach_action", "REJECT");
        p.put("priority", 50);
        List<Rule> rules = TcpCompiler.compileInstrument(p);
        Rule perTxn = rules.stream().filter(r -> "PER_TRANSACTION".equals(r.period()) && "AMOUNT".equals(r.measure()))
                .findFirst().orElseThrow();
        assertThat(perTxn.scope()).isEqualTo("INSTRUMENT_GRADE");
        assertThat(perTxn.instrument()).isEqualTo("WALLET");
        assertThat(perTxn.gradeCode()).isEqualTo("GOLD");
        assertThat(perTxn.maxAmount()).isEqualByComparingTo("1000");
        assertThat(perTxn.perTxnMin()).isEqualByComparingTo("1");
        assertThat(perTxn.amountMultiple()).isEqualByComparingTo("10");
        assertThat(perTxn.maxTransferPct()).isEqualByComparingTo("0.5");
        assertThat(perTxn.minRemainingBalance()).isEqualByComparingTo("5");
        assertThat(rules).anyMatch(r -> "DAILY".equals(r.period()) && "AMOUNT".equals(r.measure()));
        assertThat(rules).noneMatch(Rule::regulatory);
    }

    @Test
    void rankTwoMustBeSubsetOfRankOne() {
        ObjectNode cum = cumulative("44444444-4444-4444-4444-444444444444");
        ObjectNode within = json.createObjectNode();
        within.put("daily_amount", "4000");
        within.put("monthly_amount", "40000");
        assertThat(TcpAdminService.subsetViolation(within, cum)).isEmpty();
        ObjectNode over = json.createObjectNode();
        over.put("daily_amount", "6000"); // exceeds cumulative 5000
        assertThat(TcpAdminService.subsetViolation(over, cum)).isPresent();
    }

    @Test
    void transferRuleMatrixMatchesAndValidatesAmount() {
        ObjectNode rule = json.createObjectNode();
        rule.put("code", "DENY_OUT");
        rule.put("sender_instrument", "WALLET");
        rule.put("receiver_instrument", "BANK_ACCOUNT");
        rule.put("hierarchy_scope", "ANY");
        ObjectNode match = json.createObjectNode();
        match.put("sender_instrument", "WALLET");
        match.put("receiver_instrument", "BANK_ACCOUNT");
        assertThat(TcpAdminService.transferRuleMatches(rule, match)).isTrue();
        ObjectNode noMatch = json.createObjectNode();
        noMatch.put("sender_instrument", "CARD");
        assertThat(TcpAdminService.transferRuleMatches(rule, noMatch)).isFalse();

        ObjectNode bounded = json.createObjectNode();
        bounded.put("min_amount", "10");
        bounded.put("max_amount", "1000");
        bounded.put("amount_multiple", "5");
        assertThat(TcpAdminService.amountReason(bounded, new java.math.BigDecimal("100"))).isNull();
        assertThat(TcpAdminService.amountReason(bounded, new java.math.BigDecimal("3"))).contains("minimum");
        assertThat(TcpAdminService.amountReason(bounded, new java.math.BigDecimal("2000"))).contains("maximum");
        assertThat(TcpAdminService.amountReason(bounded, new java.math.BigDecimal("12"))).contains("multiple");
    }

    @Test
    void compileHandlesBothTiersTogether() {
        List<Rule> all = TcpCompiler.compile(List.of(cumulative(UUID.randomUUID().toString())),
                List.of(instrument()));
        assertThat(all).anyMatch(r -> "REGULATORY_PROFILE".equals(r.scope()));
        assertThat(all).anyMatch(r -> "INSTRUMENT_GRADE".equals(r.scope()));
    }

    private ObjectNode instrument() {
        ObjectNode p = json.createObjectNode();
        p.put("id", UUID.randomUUID().toString());
        p.put("code", "ITC2");
        p.put("payment_instrument", "WALLET");
        p.put("currency", "USD");
        p.put("per_txn_max", "500");
        p.put("breach_action", "REJECT");
        p.put("priority", 100);
        return p;
    }
}
