package io.meridian.approvals;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * What a service asks workflow-service to approve. {@code resourceType} + {@code action} route the decision back to
 * the owning service's {@link ApprovalOutcomeHandler}; {@code payload} is what approvers see (and what the handler
 * re-checks at execution time). Amount and currency let approval policies route by threshold.
 */
public record ApprovalSpec(String resourceType, UUID resourceId, String action, String title, String summary,
                           Object payload, BigDecimal amount, String currency, String priority) {
    public ApprovalSpec {
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(title, "title");
        priority = priority == null ? "NORMAL" : priority;
    }

    public static ApprovalSpec of(String resourceType, UUID resourceId, String action, String title, Object payload) {
        return new ApprovalSpec(resourceType, resourceId, action, title, null, payload, null, null, null);
    }

    public ApprovalSpec withAmount(BigDecimal value, String ccy) {
        return new ApprovalSpec(resourceType, resourceId, action, title, summary, payload, value, ccy, priority);
    }

    public ApprovalSpec withSummary(String text) {
        return new ApprovalSpec(resourceType, resourceId, action, title, text, payload, amount, currency, priority);
    }
}
