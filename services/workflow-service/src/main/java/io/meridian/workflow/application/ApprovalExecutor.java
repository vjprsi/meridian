package io.meridian.workflow.application;

import tools.jackson.databind.JsonNode;

/**
 * Executes an approved operation that this service owns (e.g. publishing a workflow definition). Operations owned
 * by other services are executed by them when they consume {@code platform.approval_request.approved}.
 */
public interface ApprovalExecutor {
    String resourceType();

    String action();

    /** Performs the approved operation and returns a result document; throwing marks the request EXECUTION_FAILED. */
    JsonNode execute(JsonNode approvalRequest);

    /** Called when the request ends without approval (rejected, changes requested, cancelled, expired). */
    default void abandoned(JsonNode approvalRequest) {
    }
}
