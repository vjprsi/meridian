package io.meridian.aiplatform.api;

import io.meridian.aiplatform.api.generated.LlmGatewayPoliciesApi;
import io.meridian.aiplatform.api.generated.model.LlmGatewayPoliciesRedactRequest;
import io.meridian.aiplatform.api.generated.model.LlmGatewayPolicy;
import io.meridian.aiplatform.api.generated.model.LlmGatewayPolicyCreate;
import io.meridian.aiplatform.api.generated.model.LlmGatewayPolicyUpdate;
import io.meridian.aiplatform.application.Fields;
import io.meridian.aiplatform.application.PolicyService;
import io.meridian.aiplatform.infrastructure.Store;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** LLM gateway policies: validation, redaction preview, monthly usage against budget. */
@RestController
public class LlmGatewayPoliciesController implements LlmGatewayPoliciesApi {
    private final PolicyService policies;
    private final Idempotency idempotency;
    private final Responses responses;
    private final CrudEngine crud;
    private final Store store;
    private final JsonMapper json;

    public LlmGatewayPoliciesController(PolicyService policies, Idempotency idempotency, Responses responses,
                                        CrudEngine crud, Store store, JsonMapper json) {
        this.policies = policies;
        this.idempotency = idempotency;
        this.responses = responses;
        this.crud = crud;
        this.store = store;
        this.json = json;
    }

    @Override
    public ResponseEntity<LlmGatewayPolicy> llmGatewayPoliciesCreate(String idempotencyKey, LlmGatewayPolicyCreate body) {
        TenantContext.require();
        ObjectNode values = crud.toColumns("llm_gateway_policy", body);
        policies.prepare(values, null);
        return idempotency.execute(idempotencyKey, body, LlmGatewayPolicy.class,
                () -> responses.created(crud.insert("llm_gateway_policy", values), LlmGatewayPolicy.class));
    }

    @Override
    public ResponseEntity<LlmGatewayPolicy> llmGatewayPoliciesUpdate(UUID id, String ifMatch, LlmGatewayPolicyUpdate body) {
        TenantContext.require();
        int version = Fields.ifMatch(ifMatch);
        JsonNode existing = store.require("llm_gateway_policy", id);
        ObjectNode values = crud.toColumns("llm_gateway_policy", body);
        if (values.isEmpty()) {
            return responses.ok(existing.toString(), LlmGatewayPolicy.class);
        }
        policies.prepare(values, existing);
        String row = crud.updateRow("llm_gateway_policy", Map.of("id", id), values, version).orElseThrow(() ->
                ApiException.conflict("version_conflict", "If-Match " + version + " does not match the current version; reload and retry"));
        return responses.ok(row, LlmGatewayPolicy.class);
    }

    @Override
    public ResponseEntity<Map<String, Object>> llmGatewayPoliciesUsage(UUID id, String month) {
        TenantContext.require();
        return responses.object(policies.usage(id, month));
    }

    @Override
    public ResponseEntity<Map<String, Object>> llmGatewayPoliciesRedact(UUID id, String idempotencyKey,
                                                                       LlmGatewayPoliciesRedactRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        String text = Fields.requireText(req, "text");
        return responses.object(idempotency.execute(idempotencyKey, responses.action("redact", id, req), JsonNode.class,
                () -> ResponseEntity.ok(policies.preview(id, text))));
    }
}
