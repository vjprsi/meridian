package io.meridian.agentnetwork.api;

import io.meridian.agentnetwork.api.generated.AgentFloatRequestsApi;
import io.meridian.agentnetwork.api.generated.model.AgentFloatRequest;
import io.meridian.agentnetwork.api.generated.model.AgentFloatRequestCreate;
import io.meridian.agentnetwork.api.generated.model.AgentFloatRequestsApproveRequest;
import io.meridian.agentnetwork.api.generated.model.AgentFloatRequestsCancelRequest;
import io.meridian.agentnetwork.api.generated.model.AgentFloatRequestsRejectRequest;
import io.meridian.agentnetwork.application.FloatService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Float top-ups, rebalances, transfers and withdrawals with maker-checker approval. */
@RestController
public class AgentFloatRequestsController implements AgentFloatRequestsApi {
    private final FloatService floats;
    private final Actions actions;

    public AgentFloatRequestsController(FloatService floats, Actions actions) {
        this.floats = floats;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<AgentFloatRequest> agentFloatRequestsCreate(String idempotencyKey, AgentFloatRequestCreate body) {
        return actions.create(idempotencyKey, body, AgentFloatRequest.class, () -> floats.createRequest(actions.tree(body)));
    }

    @Override
    public ResponseEntity<AgentFloatRequest> agentFloatRequestsApprove(UUID id, String idempotencyKey,
                                                                      AgentFloatRequestsApproveRequest body) {
        return actions.action(idempotencyKey, id, "approve", body, AgentFloatRequest.class,
                () -> floats.approve(id, Http.text(actions.tree(body), "comment")));
    }

    @Override
    public ResponseEntity<AgentFloatRequest> agentFloatRequestsReject(UUID id, String idempotencyKey,
                                                                     AgentFloatRequestsRejectRequest body) {
        return actions.action(idempotencyKey, id, "reject", body, AgentFloatRequest.class,
                () -> floats.close(id, "REJECTED", Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<AgentFloatRequest> agentFloatRequestsCancel(UUID id, String idempotencyKey,
                                                                     AgentFloatRequestsCancelRequest body) {
        return actions.action(idempotencyKey, id, "cancel", body, AgentFloatRequest.class,
                () -> floats.close(id, "CANCELLED", Http.text(actions.tree(body), "reason")));
    }
}
