package io.meridian.workflow.api;

import io.meridian.workflow.api.generated.ApprovalRequestsApi;
import io.meridian.workflow.api.generated.model.ApprovalDecisionPage;
import io.meridian.workflow.api.generated.model.ApprovalRequest;
import io.meridian.workflow.api.generated.model.ApprovalRequestCreate;
import io.meridian.workflow.api.generated.model.ApprovalRequestPage;
import io.meridian.workflow.api.generated.model.ApprovalRequestsApproveRequest;
import io.meridian.workflow.api.generated.model.ApprovalRequestsCancelRequest;
import io.meridian.workflow.api.generated.model.ApprovalRequestsRejectRequest;
import io.meridian.workflow.api.generated.model.ApprovalRequestsRequestChangesRequest;
import io.meridian.workflow.application.ApprovalService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Maker-checker approval requests: raise, approve / reject / request changes (four-eyes), cancel, inbox. */
@RestController
public class ApprovalRequestsController implements ApprovalRequestsApi {
    private final ApprovalService approvals;
    private final CallerResolver callers;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public ApprovalRequestsController(ApprovalService approvals, CallerResolver callers, CrudEngine crud,
                                      Idempotency idempotency, JsonMapper json) {
        this.approvals = approvals;
        this.callers = callers;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<ApprovalRequest> approvalRequestsCreate(String idempotencyKey, ApprovalRequestCreate body) {
        var caller = callers.current();
        return idempotency.execute(idempotencyKey, body, ApprovalRequest.class, () -> {
            ObjectNode values = crud.toColumns("approval_request", body);
            return ResponseEntity.status(HttpStatus.CREATED).body(model(approvals.create(values, caller)));
        });
    }

    @Override
    public ResponseEntity<ApprovalRequest> approvalRequestsApprove(UUID id, String idempotencyKey,
                                                                   ApprovalRequestsApproveRequest body) {
        var caller = callers.current();
        String comment = text(body, "comment");
        return idempotency.execute(idempotencyKey, Actions.fingerprint("approve", id, body), ApprovalRequest.class,
                () -> ResponseEntity.ok(model(approvals.approve(id, comment, caller))));
    }

    @Override
    public ResponseEntity<ApprovalRequest> approvalRequestsReject(UUID id, String idempotencyKey,
                                                                  ApprovalRequestsRejectRequest body) {
        var caller = callers.current();
        String comment = text(body, "comment");
        return idempotency.execute(idempotencyKey, Actions.fingerprint("reject", id, body), ApprovalRequest.class,
                () -> ResponseEntity.ok(model(approvals.reject(id, comment, caller))));
    }

    @Override
    public ResponseEntity<ApprovalRequest> approvalRequestsRequestChanges(UUID id, String idempotencyKey,
                                                                          ApprovalRequestsRequestChangesRequest body) {
        var caller = callers.current();
        String comment = text(body, "comment");
        return idempotency.execute(idempotencyKey, Actions.fingerprint("request-changes", id, body), ApprovalRequest.class,
                () -> ResponseEntity.ok(model(approvals.requestChanges(id, comment, caller))));
    }

    @Override
    public ResponseEntity<ApprovalRequest> approvalRequestsCancel(UUID id, String idempotencyKey,
                                                                  ApprovalRequestsCancelRequest body) {
        var caller = callers.current();
        String reason = text(body, "reason");
        return idempotency.execute(idempotencyKey, Actions.fingerprint("cancel", id, body), ApprovalRequest.class,
                () -> ResponseEntity.ok(model(approvals.cancel(id, reason, caller))));
    }

    @Override
    public ResponseEntity<ApprovalDecisionPage> approvalRequestsDecisions(UUID id, Integer limit, String cursor) {
        approvals.requireExists(id);
        return crud.list("approval_decision", ApprovalDecisionPage.class,
                Crud.params().with("approval_request_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<ApprovalRequestPage> approvalRequestsInbox(Integer limit, String cursor) {
        var caller = callers.current();
        return ResponseEntity.ok(crud.read(approvals.inbox(caller, limit, cursor), ApprovalRequestPage.class));
    }

    private ApprovalRequest model(JsonNode row) {
        return crud.read(row.toString(), ApprovalRequest.class);
    }

    private String text(Object body, String field) {
        if (body == null) {
            return null;
        }
        JsonNode n = json.valueToTree(body);
        return n.hasNonNull(field) ? n.get(field).asString() : null;
    }
}
