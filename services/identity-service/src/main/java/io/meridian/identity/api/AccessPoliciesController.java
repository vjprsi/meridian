package io.meridian.identity.api;

import io.meridian.identity.api.generated.AccessPoliciesApi;
import io.meridian.identity.api.generated.model.AccessPoliciesSimulateRequest;
import io.meridian.identity.api.generated.model.AccessPolicy;
import io.meridian.identity.api.generated.model.AccessPolicyCreate;
import io.meridian.identity.api.generated.model.AccessPolicyUpdate;
import io.meridian.identity.application.AuthorizationService;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** ABAC policies: conditions are validated on write; simulate evaluates RBAC + ABAC for a subject. */
@RestController
public class AccessPoliciesController implements AccessPoliciesApi {
    private final AuthorizationService authz;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public AccessPoliciesController(AuthorizationService authz, Idempotency idempotency, JsonMapper json) {
        this.authz = authz;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<AccessPolicy> accessPoliciesCreate(String idempotencyKey, AccessPolicyCreate body) {
        authz.validatePolicy(json.valueToTree(body));
        return AccessPoliciesApi.super.accessPoliciesCreate(idempotencyKey, body);
    }

    @Override
    public ResponseEntity<AccessPolicy> accessPoliciesUpdate(UUID id, String ifMatch, AccessPolicyUpdate body) {
        authz.validatePolicy(json.valueToTree(body));
        return AccessPoliciesApi.super.accessPoliciesUpdate(id, ifMatch, body);
    }

    @Override
    public ResponseEntity<Map<String, Object>> accessPoliciesSimulate(String idempotencyKey,
                                                                      AccessPoliciesSimulateRequest body) {
        return Responses.object(json, idempotency.execute(idempotencyKey, body, JsonNode.class,
                () -> ResponseEntity.ok(authz.simulate(body.getUserId(), body.getAction(),
                        json.valueToTree(body.getResource())))));
    }
}
