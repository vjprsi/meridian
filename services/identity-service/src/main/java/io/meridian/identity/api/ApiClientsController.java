package io.meridian.identity.api;

import io.meridian.identity.api.generated.ApiClientsApi;
import io.meridian.identity.api.generated.model.ApiClientCreate;
import io.meridian.identity.api.generated.model.ApiClientUpdate;
import io.meridian.identity.api.generated.model.ApiClientsSuspendRequest;
import io.meridian.identity.api.generated.model.ModelApiClient;
import io.meridian.identity.application.ApiClientService;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** OAuth clients: registration (secret returned once), secret rotation, suspension, usage. */
@RestController
public class ApiClientsController implements ApiClientsApi {
    private final ApiClientService clients;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;
    private final HttpServletRequest request;

    public ApiClientsController(ApiClientService clients, CrudEngine crud, Idempotency idempotency, JsonMapper json,
                                HttpServletRequest request) {
        this.clients = clients;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
        this.request = request;
    }

    @Override
    public ResponseEntity<ModelApiClient> apiClientsCreate(String idempotencyKey, ApiClientCreate body) {
        return Responses.secretOnce(idempotency, idempotencyKey, body, HttpStatus.CREATED, () -> {
            ApiClientService.Issued i = clients.register(body);
            return new Responses.WithSecrets(i.body(), i.secrets());
        });
    }

    @Override
    public ResponseEntity<ModelApiClient> apiClientsUpdate(UUID id, String ifMatch, ApiClientUpdate body) {
        return clients.update(id, ifMatch, body, ModelApiClient.class);
    }

    @Override
    public ResponseEntity<Void> apiClientsRotateSecret(UUID id, String idempotencyKey) {
        return Responses.secretOnce(idempotency, idempotencyKey, Map.of("op", "rotate-secret", "id", id), HttpStatus.OK,
                () -> {
                    Map<String, String> secrets = new HashMap<>();
                    JsonNode b = clients.rotateSecret(id, secrets);
                    return new Responses.WithSecrets(b, secrets);
                });
    }

    @Override
    public ResponseEntity<ModelApiClient> apiClientsSuspend(UUID id, String idempotencyKey, ApiClientsSuspendRequest body) {
        JsonNode req = json.valueToTree(body);
        return idempotency.execute(idempotencyKey, Map.of("op", "suspend", "id", id, "body", req), ModelApiClient.class,
                () -> ResponseEntity.ok(crud.read(clients.suspend(id, req.path("reason").asString(null)), ModelApiClient.class)));
    }

    @Override
    public ResponseEntity<ModelApiClient> apiClientsReactivate(UUID id, String idempotencyKey) {
        return idempotency.execute(idempotencyKey, Map.of("op", "reactivate", "id", id), ModelApiClient.class,
                () -> ResponseEntity.ok(crud.read(clients.reactivate(id), ModelApiClient.class)));
    }

    /** Contract defect: ?from=&to= are not declared on the operation, so they are read from the request. */
    @Override
    public ResponseEntity<Void> apiClientsUsage(UUID id) {
        return Responses.body(ResponseEntity.ok(clients.usage(id, request.getParameter("from"), request.getParameter("to"))));
    }
}
