package io.meridian.tenant.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.tenant.api.generated.CellsApi;
import io.meridian.tenant.api.generated.model.Cell;
import io.meridian.tenant.api.generated.model.CellCreate;
import io.meridian.tenant.api.generated.model.CellUpdate;
import io.meridian.tenant.api.generated.model.CellsDrainRequest;
import io.meridian.tenant.application.TenantLifecycleService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** Deployment cells (platform operator): capacity and lifecycle rules, draining. */
@RestController
public class CellsController implements CellsApi {
    private final TenantLifecycleService tenants;
    private final Idempotency idempotency;
    private final CrudEngine crud;
    private final JsonMapper json;

    public CellsController(TenantLifecycleService tenants, Idempotency idempotency, CrudEngine crud, JsonMapper json) {
        this.tenants = tenants;
        this.idempotency = idempotency;
        this.crud = crud;
        this.json = json;
    }

    @Override
    public ResponseEntity<Cell> cellsCreate(String idempotencyKey, CellCreate body) {
        TenantContext.require();
        tenants.checkCellCreate(body);
        return crud.create("cell", Cell.class, body, idempotencyKey, Crud.params());
    }

    @Override
    public ResponseEntity<Cell> cellsUpdate(UUID id, String ifMatch, CellUpdate body) {
        TenantContext.require();
        tenants.checkCellPatch(id, body);
        return crud.update("cell", Cell.class, body, ifMatch, Crud.params().with("id", id));
    }

    @Override
    public ResponseEntity<Cell> cellsDrain(UUID id, String idempotencyKey, CellsDrainRequest body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, Cell.class,
                () -> Responses.of(HttpStatus.OK, tenants.drain(id, body.getReason()), Cell.class, json));
    }
}
