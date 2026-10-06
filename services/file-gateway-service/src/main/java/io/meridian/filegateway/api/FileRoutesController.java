package io.meridian.filegateway.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.filegateway.api.generated.FileRoutesApi;
import io.meridian.filegateway.api.generated.model.FileRoute;
import io.meridian.filegateway.api.generated.model.FileRouteCreate;
import io.meridian.filegateway.api.generated.model.FileRouteUpdate;
import io.meridian.filegateway.api.generated.model.FileRoutesSimulateRequest;
import io.meridian.filegateway.application.RouteService;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Routing rules for inbound files, with a simulator. */
@RestController
public class FileRoutesController implements FileRoutesApi {
    private static final String TABLE = "file_route";

    private final RouteService routes;
    private final CrudEngine crud;
    private final Updates updates;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public FileRoutesController(RouteService routes, CrudEngine crud, Updates updates, Idempotency idempotency,
                                JsonMapper json) {
        this.routes = routes;
        this.crud = crud;
        this.updates = updates;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<FileRoute> fileRoutesCreate(String idempotencyKey, FileRouteCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, FileRoute.class, () -> {
            ObjectNode values = crud.toColumns(TABLE, body);
            routes.validate(values);
            return ResponseEntity.status(HttpStatus.CREATED).body(crud.read(crud.insert(TABLE, values), FileRoute.class));
        });
    }

    @Override
    public ResponseEntity<FileRoute> fileRoutesUpdate(UUID id, String ifMatch, FileRouteUpdate body) {
        TenantContext.require();
        return updates.apply(TABLE, id, ifMatch, crud.toColumns(TABLE, body), FileRoute.class,
                (merged, changes) -> routes.validate(merged));
    }

    @Override
    public ResponseEntity<FileRoute> fileRoutesSimulate(String idempotencyKey, FileRoutesSimulateRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        String name = Http.required(req, "file_name");
        UUID channel = req.hasNonNull("file_channel_id") ? UUID.fromString(req.get("file_channel_id").asString()) : null;
        JsonNode route = routes.match(channel, name).orElseThrow(() -> new ApiException(404, "no_matching_route",
                "No active route matches " + name));
        return ResponseEntity.ok(crud.read(route.toString(), FileRoute.class));
    }
}
