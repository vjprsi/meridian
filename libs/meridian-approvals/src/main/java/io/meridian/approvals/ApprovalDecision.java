package io.meridian.approvals;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * A final decision on an approval request, as received from workflow-service.
 *
 * @param decidedBy the approver who made the final decision (the request's {@code updated_by})
 * @param request   the full approval request row (payload, maker, approvals, execution_result, ...)
 */
public record ApprovalDecision(UUID approvalRequestId, String resourceType, UUID resourceId, String action,
                               Outcome outcome, String maker, String decidedBy, JsonNode payload, JsonNode request) {

    public enum Outcome {
        APPROVED, REJECTED, CHANGES_REQUESTED, EXPIRED, CANCELLED;

        /** Maps the event type suffix ({@code platform.approval_request.<suffix>}); null for non-final events. */
        public static Outcome ofEventSuffix(String suffix) {
            return switch (suffix) {
                case "approved" -> APPROVED;
                case "rejected" -> REJECTED;
                case "changes_requested" -> CHANGES_REQUESTED;
                case "expired" -> EXPIRED;
                case "cancelled" -> CANCELLED;
                default -> null;
            };
        }
    }

    public boolean approved() {
        return outcome == Outcome.APPROVED;
    }
}
