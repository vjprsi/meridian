package io.meridian.gl.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.gl.api.generated.GlMappingRulesApi;
import io.meridian.gl.api.generated.model.GlMappingRule;
import io.meridian.gl.api.generated.model.GlMappingRuleCreate;
import io.meridian.gl.api.generated.model.GlMappingRuleUpdate;
import io.meridian.gl.api.generated.model.GlMappingRulesSimulateRequest;
import io.meridian.gl.application.MappingService;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** GL mapping rules: validated writes; /simulate returns the GL account and the winning rule. */
@RestController
public class GlMappingRulesController implements GlMappingRulesApi {
    private final MappingService mapping;
    private final Idempotency idempotency;
    private final Responses responses;
    private final JsonMapper json;

    public GlMappingRulesController(MappingService mapping, Idempotency idempotency, Responses responses, JsonMapper json) {
        this.mapping = mapping;
        this.idempotency = idempotency;
        this.responses = responses;
        this.json = json;
    }

    @Override
    public ResponseEntity<GlMappingRule> glMappingRulesCreate(String idempotencyKey, GlMappingRuleCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, GlMappingRule.class,
                () -> responses.created(mapping.create(body), GlMappingRule.class));
    }

    @Override
    public ResponseEntity<GlMappingRule> glMappingRulesUpdate(UUID id, String ifMatch, GlMappingRuleUpdate body) {
        TenantContext.require();
        return responses.ok(mapping.update(id, ifMatch, body), GlMappingRule.class);
    }

    /** Read-only: the key is validated but no response is stored, so a simulation always reflects current rules. */
    @Override
    public ResponseEntity<Map<String, Object>> glMappingRulesSimulate(String idempotencyKey,
                                                                     GlMappingRulesSimulateRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        return responses.object(mapping.simulate(req));
    }
}
