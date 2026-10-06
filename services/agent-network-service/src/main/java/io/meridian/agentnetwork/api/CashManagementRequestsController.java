package io.meridian.agentnetwork.api;

import io.meridian.agentnetwork.api.generated.CashManagementRequestsApi;
import io.meridian.agentnetwork.api.generated.model.CashManagementRequest;
import io.meridian.agentnetwork.api.generated.model.CashManagementRequestCreate;
import io.meridian.agentnetwork.api.generated.model.CashManagementRequestUpdate;
import io.meridian.agentnetwork.api.generated.model.CashManagementRequestsApproveRequest;
import io.meridian.agentnetwork.api.generated.model.CashManagementRequestsCancelRequest;
import io.meridian.agentnetwork.api.generated.model.CashManagementRequestsCompleteRequest;
import io.meridian.agentnetwork.application.ComplianceService;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.CrudEngine;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Cash-in-transit deliveries and pickups for outlets (maker-checker approval). */
@RestController
public class CashManagementRequestsController implements CashManagementRequestsApi {
    private static final Set<String> PROTECTED = Set.of("agent_outlet_id", "request_type", "currency");

    private final ComplianceService compliance;
    private final Actions actions;
    private final CrudEngine crud;

    public CashManagementRequestsController(ComplianceService compliance, Actions actions, CrudEngine crud) {
        this.compliance = compliance;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<CashManagementRequest> cashManagementRequestsCreate(String idempotencyKey,
                                                                             CashManagementRequestCreate body) {
        return actions.create(idempotencyKey, body, CashManagementRequest.class,
                () -> compliance.createCashRequest(actions.tree(body)));
    }

    @Override
    public ResponseEntity<CashManagementRequest> cashManagementRequestsUpdate(UUID id, String ifMatch,
                                                                             CashManagementRequestUpdate body) {
        TenantContext.require();
        compliance.checkCashUpdate(id);
        if (actions.tree(body).hasNonNull("amount") && Http.decimal(actions.tree(body), "amount").signum() <= 0) {
            throw io.meridian.common.error.ApiException.unprocessable("invalid_amount", "amount must be positive");
        }
        ResponseEntity<CashManagementRequest> updated = actions.guardedUpdate(crud, "cash_management_request",
                CashManagementRequest.class, id, ifMatch, body, PROTECTED, "create a new request instead");
        compliance.resubmitCash(id); // central maker-checker: the approval follows the changed content
        return updated;
    }

    @Override
    public ResponseEntity<CashManagementRequest> cashManagementRequestsApprove(UUID id, String idempotencyKey,
                                                                              CashManagementRequestsApproveRequest body) {
        return actions.action(idempotencyKey, id, "approve", body, CashManagementRequest.class,
                () -> compliance.approveCash(id));
    }

    @Override
    public ResponseEntity<CashManagementRequest> cashManagementRequestsComplete(UUID id, String idempotencyKey,
                                                                               CashManagementRequestsCompleteRequest body) {
        return actions.action(idempotencyKey, id, "complete", body, CashManagementRequest.class,
                () -> compliance.completeCash(id, Http.text(actions.tree(body), "cit_reference")));
    }

    @Override
    public ResponseEntity<CashManagementRequest> cashManagementRequestsCancel(UUID id, String idempotencyKey,
                                                                             CashManagementRequestsCancelRequest body) {
        return actions.action(idempotencyKey, id, "cancel", body, CashManagementRequest.class,
                () -> compliance.cancelCash(id, Http.text(actions.tree(body), "reason")));
    }
}
