package io.meridian.tenant.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.tenant.api.generated.StarterPackRunsApi;
import io.meridian.tenant.api.generated.model.StarterPackRun;
import io.meridian.tenant.api.generated.model.StarterPackRunsApplyRequest;
import io.meridian.tenant.application.StarterPackService;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Starter packs for the caller's own tenant: catalog, apply / re-apply / upgrade, resume. List and get of runs are
 * generic CRUD. Contract: generated/openapi/services/tenant-service.openapi.yaml
 */
@RestController
public class StarterPackRunsController implements StarterPackRunsApi {
    private final StarterPackService starterPacks;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public StarterPackRunsController(StarterPackService starterPacks, Idempotency idempotency, JsonMapper json) {
        this.starterPacks = starterPacks;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<Map<String, Object>> starterPackRunsCatalog(String packCode) {
        TenantContext.require();
        JsonNode out = packCode == null || packCode.isBlank()
                ? starterPacks.catalog().summary(json)
                : starterPacks.catalog().require(packCode, null).definition();
        return ResponseEntity.ok(json.convertValue(out, new TypeReference<Map<String, Object>>() {}));
    }

    @Override
    public ResponseEntity<StarterPackRun> starterPackRunsApply(String idempotencyKey, StarterPackRunsApplyRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        return idempotency.execute(idempotencyKey, body, StarterPackRun.class, () -> Responses.of(HttpStatus.OK,
                starterPacks.applyToCaller(req.path("pack_code").asString(), req.path("version").asString(null),
                        req.get("parameters"), req.path("dry_run").asBoolean(false)),
                StarterPackRun.class, json));
    }

    @Override
    public ResponseEntity<StarterPackRun> starterPackRunsResume(UUID id, String idempotencyKey) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, "resume:" + id, StarterPackRun.class,
                () -> Responses.of(HttpStatus.OK, starterPacks.resume(id), StarterPackRun.class, json));
    }
}
