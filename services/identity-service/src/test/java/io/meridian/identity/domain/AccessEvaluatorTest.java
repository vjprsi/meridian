package io.meridian.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AccessEvaluatorTest {
    static final Map<String, Object> CTX = Map.of(
            "subject", Map.of("branch_id", "b1", "roles", List.of("TELLER")),
            "resource", Map.of("amount", 2500, "branch_id", "b1", "currency", "KES"),
            "action", "payments.payment.approve");

    @Test
    void expressionSubset() {
        assertThat(PolicyExpression.parse("resource.amount <= 10000 && subject.branch_id == resource.branch_id")
                .evaluate(CTX)).isTrue();
        assertThat(PolicyExpression.parse("resource.amount > 10000 || resource.currency != 'KES'").evaluate(CTX)).isFalse();
        assertThat(PolicyExpression.parse("!(resource.currency in ['USD', \"EUR\"]) and 'TELLER' in subject.roles")
                .evaluate(CTX)).isTrue();
        assertThat(PolicyExpression.parse("resource.missing == null && !(resource.missing < 5)").evaluate(CTX))
                .isTrue();
        assertThat(PolicyExpression.parse("resource.amount >= -1.5").evaluate(CTX)).isTrue();
        assertThatThrownBy(() -> PolicyExpression.parse("resource.amount <=")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PolicyExpression.parse("a; drop table")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PolicyExpression.parse("resource.amount").evaluate(CTX))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void denyWinsOnTieAndLowerPriorityFirst() {
        var allow = new AccessEvaluator.Policy("a", "allow-own-branch", "ALLOW", 10, "payments.*", List.of("approve"),
                "subject.branch_id == resource.branch_id");
        var deny = new AccessEvaluator.Policy("d", "deny-large", "DENY", 10, "payments.payment.*", List.of("*"),
                "resource.amount > 1000");
        var laterDeny = new AccessEvaluator.Policy("x", "later", "DENY", 50, "*", List.of("*"), "true");
        assertThat(AccessEvaluator.decide("payments.payment.approve", true, List.of(allow, deny, laterDeny), CTX).decision())
                .isEqualTo("DENY");
        var d = AccessEvaluator.decide("payments.payment.approve", true, List.of(allow, laterDeny), CTX);
        assertThat(d.decision()).isEqualTo("ALLOW");
        assertThat(d.decidingPolicyId()).isEqualTo("a");
        assertThat(AccessEvaluator.decide("payments.payment.approve", false, List.of(allow), CTX).reason())
                .isEqualTo("no_permission");
        assertThat(AccessEvaluator.decide("cards.card.read", true, List.of(allow), CTX).reason()).isEqualTo("rbac_granted");
    }

    @Test
    void userLifecycle() {
        assertThat(UserLifecycle.next("ACTIVE", UserLifecycle.Action.LOCK)).isEqualTo("LOCKED");
        assertThat(UserLifecycle.next("LOCKED", UserLifecycle.Action.UNLOCK)).isEqualTo("ACTIVE");
        assertThatThrownBy(() -> UserLifecycle.next("DEACTIVATED", UserLifecycle.Action.UNLOCK))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> UserLifecycle.next("DEACTIVATED", UserLifecycle.Action.DEACTIVATE))
                .isInstanceOf(IllegalStateException.class);
    }
}
