package io.meridian.agentchannel.api;

import io.meridian.agentchannel.api.generated.AgentMeTillsApi;
import io.meridian.agentchannel.api.generated.model.AgentMeTillsCloseRequest;
import io.meridian.agentchannel.api.generated.model.AgentMeTillsCreateRequest;
import io.meridian.agentchannel.api.generated.model.AgentMeTillsOpenRequest;
import io.meridian.agentchannel.api.generated.model.AgentTill;
import io.meridian.agentchannel.api.generated.model.AgentTillPage;
import io.meridian.agentchannel.application.OutletService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Tills at the agent's outlets: create, open and close with cash counts. */
@RestController
public class AgentMeTillsController implements AgentMeTillsApi {
    private final OutletService outlets;
    private final Http http;

    public AgentMeTillsController(OutletService outlets, Http http) {
        this.outlets = outlets;
        this.http = http;
    }

    @Override
    public ResponseEntity<AgentTillPage> agentMeTillsList(UUID agentOutletId, String status, Integer limit, String cursor) {
        return http.ok(outlets.tills(agentOutletId, status, limit, cursor), AgentTillPage.class);
    }

    @Override
    public ResponseEntity<AgentTill> agentMeTillsCreate(String idempotencyKey, AgentMeTillsCreateRequest body) {
        return http.ok(outlets.createTill(http.body(body), idempotencyKey), AgentTill.class);
    }

    @Override
    public ResponseEntity<AgentTill> agentMeTillsGet(UUID tillId) {
        return http.ok(outlets.till(tillId), AgentTill.class);
    }

    @Override
    public ResponseEntity<AgentTill> agentMeTillsOpen(UUID tillId, String idempotencyKey, AgentMeTillsOpenRequest body) {
        return http.ok(outlets.openTill(tillId, http.body(body), idempotencyKey), AgentTill.class);
    }

    @Override
    public ResponseEntity<AgentTill> agentMeTillsClose(UUID tillId, String idempotencyKey, AgentMeTillsCloseRequest body) {
        return http.ok(outlets.closeTill(tillId, http.body(body), idempotencyKey), AgentTill.class);
    }
}
