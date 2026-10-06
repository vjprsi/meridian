package io.meridian.workflow.api;

import io.meridian.workflow.api.generated.ApprovalPoliciesApi;
import io.meridian.workflow.api.generated.model.ApprovalPolicy;
import io.meridian.workflow.api.generated.model.ApprovalPolicyCreate;
import io.meridian.workflow.api.generated.model.ApprovalPolicyUpdate;
import io.meridian.workflow.application.ApprovalService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Approval policies: generic CRUD plus validation of the level / threshold configuration. */
@RestController
public class ApprovalPoliciesController implements ApprovalPoliciesApi {
    private final ApprovalService approvals;
    private final CrudEngine crud;
    private final JsonMapper json;

    public ApprovalPoliciesController(ApprovalService approvals, CrudEngine crud, JsonMapper json) {
        this.approvals = approvals;
        this.crud = crud;
        this.json = json;
    }

    @Override
    public ResponseEntity<ApprovalPolicy> approvalPoliciesCreate(String idempotencyKey, ApprovalPolicyCreate body) {
        approvals.validatePolicy(json.valueToTree(body.getApprovalLevels()), body.getRequiredApprovals(),
                body.getExpiresAfterHours());
        return crud.create("approval_policy", ApprovalPolicy.class, body, idempotencyKey, Crud.params());
    }

    @Override
    public ResponseEntity<ApprovalPolicy> approvalPoliciesUpdate(UUID id, String ifMatch, ApprovalPolicyUpdate body) {
        ObjectNode changes = crud.toColumns("approval_policy", body);
        if (changes.has("approval_levels") || changes.has("required_approvals") || changes.has("expires_after_hours")) {
            JsonNode current = json.readTree(crud.findOne("approval_policy", Map.of("id", id))
                    .orElseThrow(() -> io.meridian.common.error.ApiException.notFound("approval policy", id)));
            JsonNode levels = changes.has("approval_levels") ? changes.get("approval_levels") : current.get("approval_levels");
            Integer required = changes.has("required_approvals") ? changes.get("required_approvals").asInt()
                    : current.path("required_approvals").asInt(1);
            approvals.validatePolicy(levels, required, body.getExpiresAfterHours());
        }
        return crud.update("approval_policy", ApprovalPolicy.class, body, ifMatch, Crud.params().with("id", id));
    }
}
