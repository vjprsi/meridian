package io.meridian.productcatalog.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.productcatalog.api.generated.ProductSimulationsApi;
import io.meridian.productcatalog.api.generated.model.ProductSimulation;
import io.meridian.productcatalog.api.generated.model.ProductSimulationCreate;
import io.meridian.productcatalog.application.SimulationService;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** Simulations: run on creation, cancel, CSV export. */
@RestController
public class ProductSimulationsController implements ProductSimulationsApi {
    private final SimulationService simulations;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public ProductSimulationsController(SimulationService simulations, CrudEngine crud, Idempotency idempotency,
                                        JsonMapper json) {
        this.simulations = simulations;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<ProductSimulation> productSimulationsCreate(String idempotencyKey, ProductSimulationCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, ProductSimulation.class, () -> {
            String row = simulations.create(body);
            return ApiSupport.withEtag(HttpStatus.CREATED, crud.read(row, ProductSimulation.class), json.readTree(row));
        });
    }

    @Override
    public ResponseEntity<ProductSimulation> productSimulationsCancel(UUID id, String idempotencyKey) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, ApiSupport.idem(json, "cancel", id, null), ProductSimulation.class, () -> {
            String row = simulations.cancel(id);
            return ApiSupport.withEtag(HttpStatus.OK, crud.read(row, ProductSimulation.class), json.readTree(row));
        });
    }

    @Override
    public ResponseEntity<Void> productSimulationsExport(UUID id) {
        TenantContext.require();
        return ApiSupport.document(new MediaType("text", "csv"), simulations.exportCsv(id));
    }
}
