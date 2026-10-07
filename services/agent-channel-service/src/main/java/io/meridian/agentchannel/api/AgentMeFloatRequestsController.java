package io.meridian.agentchannel.api;

import io.meridian.agentchannel.api.generated.AgentMeFloatRequestsApi;
import io.meridian.agentchannel.api.generated.model.AgentFloatRequest;
import io.meridian.agentchannel.api.generated.model.AgentFloatRequestPage;
import io.meridian.agentchannel.api.generated.model.AgentMeFloatRequestsCancelRequest;
import io.meridian.agentchannel.api.generated.model.AgentMeFloatRequestsCreateRequest;
import io.meridian.agentchannel.application.OutletService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The agent's float requests (top-up, rebalance). */
@RestController
public class AgentMeFloatRequestsController implements AgentMeFloatRequestsApi {
    private final OutletService outlets;
    private final Http http;

    public AgentMeFloatRequestsController(OutletService outlets, Http http) {
        this.outlets = outlets;
        this.http = http;
    }

    @Override
    public ResponseEntity<AgentFloatRequestPage> agentMeFloatRequestsList(String status, String idempotencyKey,
                                                                          Integer limit, String cursor) {
        return http.ok(outlets.floatRequests(status, idempotencyKey, limit, cursor), AgentFloatRequestPage.class);
    }

    @Override
    public ResponseEntity<AgentFloatRequest> agentMeFloatRequestsCreate(String idempotencyKey,
                                                                       AgentMeFloatRequestsCreateRequest body) {
        return http.ok(outlets.createFloatRequest(http.body(body), idempotencyKey), AgentFloatRequest.class);
    }

    @Override
    public ResponseEntity<AgentFloatRequest> agentMeFloatRequestsGet(UUID floatRequestId) {
        return http.ok(outlets.floatRequest(floatRequestId), AgentFloatRequest.class);
    }

    @Override
    public ResponseEntity<AgentFloatRequest> agentMeFloatRequestsCancel(UUID floatRequestId, String idempotencyKey,
                                                                       AgentMeFloatRequestsCancelRequest body) {
        return http.ok(outlets.cancelFloatRequest(floatRequestId, http.body(body), idempotencyKey),
                AgentFloatRequest.class);
    }
}
