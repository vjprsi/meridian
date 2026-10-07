package io.meridian.agentchannel.api;

import io.meridian.agentchannel.api.generated.AgentMeCashRequestsApi;
import io.meridian.agentchannel.api.generated.model.AgentMeCashRequestsCancelRequest;
import io.meridian.agentchannel.api.generated.model.AgentMeCashRequestsCreateRequest;
import io.meridian.agentchannel.api.generated.model.CashManagementRequest;
import io.meridian.agentchannel.api.generated.model.CashManagementRequestPage;
import io.meridian.agentchannel.application.OutletService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Cash delivery / pickup requests of the agent's outlets. */
@RestController
public class AgentMeCashRequestsController implements AgentMeCashRequestsApi {
    private final OutletService outlets;
    private final Http http;

    public AgentMeCashRequestsController(OutletService outlets, Http http) {
        this.outlets = outlets;
        this.http = http;
    }

    @Override
    public ResponseEntity<CashManagementRequestPage> agentMeCashRequestsList(UUID agentOutletId, String status,
                                                                             Integer limit, String cursor) {
        return http.ok(outlets.cashRequests(agentOutletId, status, limit, cursor), CashManagementRequestPage.class);
    }

    @Override
    public ResponseEntity<CashManagementRequest> agentMeCashRequestsCreate(String idempotencyKey,
                                                                          AgentMeCashRequestsCreateRequest body) {
        return http.ok(outlets.createCashRequest(http.body(body), idempotencyKey), CashManagementRequest.class);
    }

    @Override
    public ResponseEntity<CashManagementRequest> agentMeCashRequestsGet(UUID cashRequestId) {
        return http.ok(outlets.cashRequest(cashRequestId), CashManagementRequest.class);
    }

    @Override
    public ResponseEntity<CashManagementRequest> agentMeCashRequestsCancel(UUID cashRequestId, String idempotencyKey,
                                                                          AgentMeCashRequestsCancelRequest body) {
        return http.ok(outlets.cancelCashRequest(cashRequestId, http.body(body), idempotencyKey),
                CashManagementRequest.class);
    }
}
