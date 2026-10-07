package io.meridian.businesschannel.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.businesschannel.domain.ApprovalPolicy.Plan;
import io.meridian.businesschannel.domain.ApprovalPolicy.Rule;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApprovalPolicyTest {
    final UUID maker = UUID.randomUUID();
    final UUID a = UUID.randomUUID();
    final UUID b = UUID.randomUUID();
    final UUID c = UUID.randomUUID();

    final List<Rule> rules = List.of(
            new Rule(UUID.randomUUID(), "ALL", "KES", new BigDecimal("0"), new BigDecimal("999.99"), 1, "ANY_ORDER", List.of()),
            new Rule(UUID.randomUUID(), "ALL", "KES", new BigDecimal("1000"), new BigDecimal("49999.99"), 2, "ANY_ORDER", List.of()),
            new Rule(UUID.randomUUID(), "SINGLE", "KES", new BigDecimal("50000"), null, 2, "SEQUENTIAL", List.of(b, a)),
            new Rule(UUID.randomUUID(), "BULK", "KES", new BigDecimal("50000"), null, 3, "ANY_ORDER", List.of()));

    @Test
    void bandsPickTheMatchingRuleOrTheDefault() {
        assertThat(ApprovalPolicy.plan(rules, "SINGLE", "KES", new BigDecimal("999.99"), 1).required()).isEqualTo(1);
        assertThat(ApprovalPolicy.plan(rules, "SINGLE", "KES", new BigDecimal("1000"), 1).required()).isEqualTo(2);
        assertThat(ApprovalPolicy.plan(rules, "SINGLE", "KES", new BigDecimal("60000"), 1).sequential()).isTrue();
        assertThat(ApprovalPolicy.plan(rules, "BULK", "KES", new BigDecimal("60000"), 1).required()).isEqualTo(3);
        Plan usd = ApprovalPolicy.plan(rules, "SINGLE", "USD", new BigDecimal("5"), 2);
        assertThat(usd.ruleId()).isNull();
        assertThat(usd.required()).isEqualTo(2);
        assertThat(ApprovalPolicy.plan(List.of(), "SINGLE", "USD", BigDecimal.ONE, 0).required()).isEqualTo(1);
    }

    @Test
    void approvalsAreDistinctNeverTheInitiatorAndInSequence() {
        Plan any = new Plan(null, 2, "ANY_ORDER", List.of());
        assertThat(ApprovalPolicy.refusal(any, maker, List.of(), maker)).contains("self_approval_forbidden");
        assertThat(ApprovalPolicy.refusal(any, maker, List.of(a), a)).contains("already_decided");
        assertThat(ApprovalPolicy.refusal(any, maker, List.of(a), b)).isEmpty();
        Plan pool = new Plan(null, 1, "ANY_ORDER", List.of(a, b));
        assertThat(ApprovalPolicy.refusal(pool, maker, List.of(), c)).contains("not_designated_approver");
        Plan seq = new Plan(null, 2, "SEQUENTIAL", List.of(b, a));
        assertThat(ApprovalPolicy.refusal(seq, maker, List.of(), a)).contains("out_of_sequence");
        assertThat(ApprovalPolicy.refusal(seq, maker, List.of(), b)).isEmpty();
        assertThat(ApprovalPolicy.refusal(seq, maker, List.of(b), a)).isEmpty();
        assertThat(ApprovalPolicy.complete(seq, 2)).isTrue();
        assertThat(ApprovalPolicy.unsatisfiable(new Plan(null, 2, "SEQUENTIAL", List.of(maker, a)), maker)).isPresent();
        assertThat(ApprovalPolicy.unsatisfiable(new Plan(null, 2, "ANY_ORDER", List.of(maker, a)), maker)).isPresent();
        assertThat(ApprovalPolicy.unsatisfiable(new Plan(null, 2, "ANY_ORDER", List.of(maker, a, b)), maker)).isEmpty();
    }

    @Test
    void rulesAreValidatedAndOverlapsDetected() {
        assertThat(ApprovalPolicy.problems(new Rule(null, "ALL", "KES", BigDecimal.ZERO, null, 0, "ANY_ORDER", List.of())))
                .isNotEmpty();
        assertThat(ApprovalPolicy.problems(new Rule(null, "ALL", "KES", BigDecimal.TEN, BigDecimal.ONE, 1, "ANY_ORDER",
                List.of()))).isNotEmpty();
        assertThat(ApprovalPolicy.problems(new Rule(null, "ALL", "KES", BigDecimal.ZERO, null, 2, "SEQUENTIAL", List.of(a))))
                .isNotEmpty();
        assertThat(ApprovalPolicy.overlaps(rules.get(0), rules.get(1))).isFalse();
        assertThat(ApprovalPolicy.overlaps(rules.get(2), rules.get(3))).isFalse();      // SINGLE vs BULK
        assertThat(ApprovalPolicy.overlaps(rules.get(1), new Rule(null, "BULK", "KES", new BigDecimal("500"),
                new BigDecimal("1500"), 1, "ANY_ORDER", List.of()))).isTrue();
        assertThat(ApprovalPolicy.overlaps(rules.get(1), new Rule(null, "ALL", "USD", BigDecimal.ZERO, null, 1,
                "ANY_ORDER", List.of()))).isFalse();
    }
}
