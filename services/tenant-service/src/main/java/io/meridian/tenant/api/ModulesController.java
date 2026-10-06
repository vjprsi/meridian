package io.meridian.tenant.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.tenant.api.generated.ModulesApi;
import io.meridian.tenant.api.generated.model.ApprovalRequest;
import io.meridian.tenant.api.generated.model.ModulesDisableRequest;
import io.meridian.tenant.api.generated.model.ModulesEnableRequest;
import io.meridian.tenant.api.generated.model.TenantModule;
import io.meridian.tenant.api.generated.model.TenantModuleUpdate;
import io.meridian.tenant.application.ModuleService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Platform modules of the tenant; enabling and disabling is maker-checker (approval in workflow-service). */
@RestController
public class ModulesController implements ModulesApi {
    private final ModuleService modules;
    private final Idempotency idempotency;
    private final CrudEngine crud;
    private final JsonMapper json;

    public ModulesController(ModuleService modules, Idempotency idempotency, CrudEngine crud, JsonMapper json) {
        this.modules = modules;
        this.idempotency = idempotency;
        this.crud = crud;
        this.json = json;
    }

    @Override
    public ResponseEntity<TenantModule> modulesUpdate(UUID id, String ifMatch, TenantModuleUpdate body) {
        TenantContext.require();
        modules.checkPatch(id, body);
        return crud.update("tenant_module", TenantModule.class, body, ifMatch, Crud.params().with("id", id));
    }

    @Override
    public ResponseEntity<ApprovalRequest> modulesEnable(UUID id, String idempotencyKey, ModulesEnableRequest body) {
        TenantContext.require();
        JsonNode req = body == null ? json.createObjectNode() : json.valueToTree(body);
        return idempotency.execute(idempotencyKey, req, ApprovalRequest.class,
                () -> Responses.of(HttpStatus.OK, modules.requestEnable(id, req.get("config"), idempotencyKey),
                        ApprovalRequest.class, json));
    }

    @Override
    public ResponseEntity<ApprovalRequest> modulesDisable(UUID id, String idempotencyKey, ModulesDisableRequest body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, ApprovalRequest.class,
                () -> Responses.of(HttpStatus.OK, modules.requestDisable(id, body.getReason(), idempotencyKey),
                        ApprovalRequest.class, json));
    }
}
