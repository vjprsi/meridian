package io.meridian.workflow.domain;

import io.meridian.common.error.ApiException;
import java.util.Map;
import java.util.Set;

/**
 * Allowed status transitions per aggregate. Every status change in this service is checked here first;
 * anything not listed is rejected with {@code 409 invalid_transition}.
 */
public final class StateMachine {
    private final String entity;
    private final Map<String, Set<String>> allowed;

    private StateMachine(String entity, Map<String, Set<String>> allowed) {
        this.entity = entity;
        this.allowed = allowed;
    }

    public static final StateMachine APPROVAL_REQUEST = new StateMachine("approval request", Map.of(
            "PENDING", Set.of("APPROVED", "REJECTED", "CHANGES_REQUESTED", "CANCELLED", "EXPIRED"),
            "APPROVED", Set.of("EXECUTED", "EXECUTION_FAILED"),
            "EXECUTION_FAILED", Set.of("EXECUTED")));

    public static final StateMachine WORKFLOW_DEFINITION = new StateMachine("workflow definition", Map.of(
            "DRAFT", Set.of("PUBLISHED"),
            "PUBLISHED", Set.of("DEPRECATED")));

    public static final StateMachine WORKFLOW_INSTANCE = new StateMachine("workflow instance", Map.of(
            "RUNNING", Set.of("WAITING", "COMPLETED", "FAILED", "CANCELLED"),
            "WAITING", Set.of("RUNNING", "CANCELLED"),
            "FAILED", Set.of("RUNNING", "CANCELLED"),
            "SUSPENDED", Set.of("RUNNING", "CANCELLED")));

    public static final StateMachine WORK_TASK = new StateMachine("task", Map.of(
            "OPEN", Set.of("CLAIMED", "ESCALATED", "CANCELLED"),
            "ESCALATED", Set.of("CLAIMED", "CANCELLED"),
            "CLAIMED", Set.of("OPEN", "CLAIMED", "IN_PROGRESS", "COMPLETED", "ESCALATED", "CANCELLED"),
            "IN_PROGRESS", Set.of("OPEN", "CLAIMED", "COMPLETED", "ESCALATED", "CANCELLED")));

    public boolean can(String from, String to) {
        return allowed.getOrDefault(from, Set.of()).contains(to);
    }

    public void require(String from, String to) {
        if (!can(from, to)) {
            throw ApiException.conflict("invalid_transition",
                    "Cannot move " + entity + " from " + from + " to " + to);
        }
    }

    public boolean isTerminal(String status) {
        return allowed.getOrDefault(status, Set.of()).isEmpty();
    }
}
