package io.meridian.tenant.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.tenant.api.generated.CellsApi;
import io.meridian.tenant.api.generated.model.Cell;
import io.meridian.tenant.api.generated.model.CellCreate;
import io.meridian.tenant.api.generated.model.CellUpdate;
import io.meridian.tenant.api.generated.model.CellsDrainRequest;
import io.meridian.tenant.api.generated.model.CellsOfflineRequest;
import io.meridian.tenant.api.generated.model.CellsOnlineRequest;
import io.meridian.tenant.api.generated.model.CellsReadOnlyRequest;
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
 * Deployment cells (platform operator): capacity, deployment model and lifecycle rules, draining, and the serving
 * states the cell router acts on (OFFLINE = fail tenants over to their standby cells, READ_ONLY = refuse writes).
 */
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
        return crud.create("cell", Cell.class, tenants.normalizeCellCreate(body), idempotencyKey, Crud.params());
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

    @Override
    public ResponseEntity<Cell> cellsOffline(UUID id, String idempotencyKey, CellsOfflineRequest body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, Cell.class, () -> Responses.of(HttpStatus.OK,
                tenants.setCellServing(id, "OFFLINE", body.getReason()), Cell.class, json));
    }

    @Override
    public ResponseEntity<Cell> cellsReadOnly(UUID id, String idempotencyKey, CellsReadOnlyRequest body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, Cell.class, () -> Responses.of(HttpStatus.OK,
                tenants.setCellServing(id, "READ_ONLY", body.getReason()), Cell.class, json));
    }

    @Override
    public ResponseEntity<Cell> cellsOnline(UUID id, String idempotencyKey, CellsOnlineRequest body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, Cell.class, () -> Responses.of(HttpStatus.OK,
                tenants.setCellServing(id, "ACTIVE", body.getReason()), Cell.class, json));
    }
}
