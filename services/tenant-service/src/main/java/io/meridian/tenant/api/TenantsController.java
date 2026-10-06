package io.meridian.tenant.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.tenant.api.generated.TenantsApi;
import io.meridian.tenant.api.generated.model.Tenant;
import io.meridian.tenant.api.generated.model.TenantCreate;
import io.meridian.tenant.api.generated.model.TenantPlacement;
import io.meridian.tenant.api.generated.model.TenantPlacementPage;
import io.meridian.tenant.api.generated.model.TenantUpdate;
import io.meridian.tenant.api.generated.model.TenantsMigrateRequest;
import io.meridian.tenant.api.generated.model.TenantsProvisionFromTemplateRequest;
import io.meridian.tenant.api.generated.model.TenantsReinstateRequest;
import io.meridian.tenant.api.generated.model.TenantsSuspendRequest;
import io.meridian.tenant.application.TenantLifecycleService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tenants API (platform operator): lifecycle actions, placement and migration between cells, template provisioning.
 * Contract: generated/openapi/services/tenant-service.openapi.yaml
 */
@RestController
public class TenantsController implements TenantsApi {
    private final TenantLifecycleService tenants;
    private final Idempotency idempotency;
    private final CrudEngine crud;
    private final JsonMapper json;

    public TenantsController(TenantLifecycleService tenants, Idempotency idempotency, CrudEngine crud, JsonMapper json) {
        this.tenants = tenants;
        this.idempotency = idempotency;
        this.crud = crud;
        this.json = json;
    }

    @Override
    public ResponseEntity<Tenant> tenantsCreate(String idempotencyKey, TenantCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, Tenant.class,
                () -> Responses.of(HttpStatus.CREATED, tenants.create(body), Tenant.class, json));
    }

    @Override
    public ResponseEntity<Tenant> tenantsUpdate(UUID id, String ifMatch, TenantUpdate body) {
        TenantContext.require();
        tenants.checkTenantPatch(id, body);
        return crud.update("tenant", Tenant.class, body, ifMatch, Crud.params().with("id", id));
    }

    @Override
    public ResponseEntity<Tenant> tenantsProvisionFromTemplate(String idempotencyKey, TenantsProvisionFromTemplateRequest body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, Tenant.class,
                () -> Responses.of(HttpStatus.OK, tenants.provisionFromTemplate(json.valueToTree(body)), Tenant.class, json));
    }

    @Override
    public ResponseEntity<Tenant> tenantsActivate(UUID id, String idempotencyKey) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, "activate:" + id, Tenant.class,
                () -> Responses.of(HttpStatus.OK, tenants.activate(id), Tenant.class, json));
    }

    @Override
    public ResponseEntity<Tenant> tenantsSuspend(UUID id, String idempotencyKey, TenantsSuspendRequest body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, Tenant.class,
                () -> Responses.of(HttpStatus.OK, tenants.suspend(id, body.getReason()), Tenant.class, json));
    }

    @Override
    public ResponseEntity<Tenant> tenantsReinstate(UUID id, String idempotencyKey, TenantsReinstateRequest body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, Tenant.class,
                () -> Responses.of(HttpStatus.OK, tenants.reinstate(id, body.getReason()), Tenant.class, json));
    }

    @Override
    public ResponseEntity<TenantPlacement> tenantsMigrate(UUID id, String idempotencyKey, TenantsMigrateRequest body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, TenantPlacement.class,
                () -> Responses.of(HttpStatus.OK, tenants.migrate(id, body.getCellId(), body.getScheduledAt()),
                        TenantPlacement.class, json));
    }

    @Override
    public ResponseEntity<TenantPlacementPage> tenantsPlacements(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        tenants.requireTenant(id);
        return crud.list("tenant_placement", TenantPlacementPage.class,
                Crud.params().with("subject_tenant_id", id).with("limit", limit).with("cursor", cursor));
    }
}
