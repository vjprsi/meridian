package io.meridian.approvals;

/**
 * Implemented by a service for each kind of resource it sends to workflow-service for approval. Called with the
 * tenant bound and the principal set to the deciding approver, outside any transaction (open one in the handler).
 * Must be idempotent: events are delivered at least once, so check the resource is still waiting for this
 * approval request before acting.
 */
public interface ApprovalOutcomeHandler {
    /** The {@code resource_type} this handler owns (unique per service). */
    String resourceType();

    void onDecided(ApprovalDecision decision);
}
