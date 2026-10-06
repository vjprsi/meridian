package io.meridian.carddispute.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.carddispute.domain.DisputeStateMachine.Outcome;
import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Applies workflow-service decisions on issuer write-offs proposed by a dispute's creator (G11). Approval replays
 * the proposed close with the deciding approver as principal, so the four-eyes rule (not the dispute creator) and
 * every close validation still apply. A decision for a dispute that was closed or changed since the proposal
 * (row_version) is ignored.
 */
@Component
public class WriteOffApprovalHandler implements ApprovalOutcomeHandler {
    private final DisputeService disputes;

    public WriteOffApprovalHandler(DisputeService disputes) {
        this.disputes = disputes;
    }

    @Override
    public String resourceType() {
        return DisputeService.WRITE_OFF_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        JsonNode p = d.payload();
        Integer rowVersion = p.hasNonNull("row_version") ? p.get("row_version").asInt() : null;
        if (d.resourceId() == null || !DisputeService.WRITE_OFF_ACTION.equals(d.action())
                || !disputes.awaitsWriteOff(d.resourceId(), rowVersion)) {
            return; // closed directly, changed or already decided: nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> {
                String note = p.hasNonNull("note") ? p.get("note").asString()
                        : "write-off approved in workflow request " + d.approvalRequestId();
                boolean liable = p.path("customer_liable").asBoolean(true);
                if (DisputeService.ACCEPT_REPRESENTMENT.equals(p.path("close_action").asString(null))) {
                    disputes.acceptRepresentmentApproved(d.resourceId(), note, liable);
                } else {
                    disputes.closeApproved(d.resourceId(), Outcome.valueOf(p.get("outcome").asString()),
                            new BigDecimal(p.get("recovered_amount").asString()), liable, note);
                }
            }
            case REJECTED, CHANGES_REQUESTED -> disputes.writeOffRejected(d.resourceId(), d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // the dispute stays open; it can be closed directly by another user
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
