package io.meridian.approvals;

import java.util.List;

/**
 * Whether the decision listener runs: in workflow mode, or when any handler is {@link
 * ApprovalOutcomeHandler#centralOnly() central-only}; never when {@code meridian.approvals.listener-enabled=false}.
 */
public class ApprovalsListenerSwitch {
    private final boolean active;

    public ApprovalsListenerSwitch(boolean listenerEnabled, String mode, List<ApprovalOutcomeHandler> handlers) {
        this.active = listenerEnabled
                && ("workflow".equalsIgnoreCase(mode) || handlers.stream().anyMatch(ApprovalOutcomeHandler::centralOnly));
    }

    public boolean active() {
        return active;
    }
}
