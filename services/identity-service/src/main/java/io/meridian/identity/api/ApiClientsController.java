package io.meridian.identity.api;

import io.meridian.identity.api.generated.ApiClientsApi;
import io.meridian.identity.api.generated.model.ApiClientCreate;
import io.meridian.identity.api.generated.model.ApiClientUpdate;
import io.meridian.identity.api.generated.model.ApiClientsSuspendRequest;
import io.meridian.identity.api.generated.model.ModelApiClient;
import io.meridian.identity.application.ApiClientService;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.time.OffsetDateTime;
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

    public ApiClientsController(ApiClientService clients, CrudEngine crud, Idempotency idempotency, JsonMapper json) {
        this.clients = clients;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<ModelApiClient> apiClientsCreate(String idempotencyKey, ApiClientCreate body) {
        return Responses.secretOnce(idempotency, json, idempotencyKey, body, HttpStatus.CREATED, ModelApiClient.class,
                () -> {
                    ApiClientService.Issued i = clients.register(body);
                    return new Responses.WithSecrets(i.body(), i.secrets());
                }, ApiClientsController::reveal);
    }

    @Override
    public ResponseEntity<ModelApiClient> apiClientsUpdate(UUID id, String ifMatch, ApiClientUpdate body) {
        return clients.update(id, ifMatch, body, ModelApiClient.class);
    }

    @Override
    public ResponseEntity<ModelApiClient> apiClientsRotateSecret(UUID id, String idempotencyKey) {
        return Responses.secretOnce(idempotency, json, idempotencyKey, Map.of("op", "rotate-secret", "id", id),
                HttpStatus.OK, ModelApiClient.class, () -> {
                    ApiClientService.Issued i = clients.rotateSecret(id);
                    return new Responses.WithSecrets(i.body(), i.secrets());
                }, ApiClientsController::reveal);
    }

    private static void reveal(ModelApiClient c, Map<String, String> secrets) {
        c.setClientSecret(secrets.get("client_secret"));
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

    @Override
    public ResponseEntity<Map<String, Object>> apiClientsUsage(UUID id, OffsetDateTime from, OffsetDateTime to) {
        return ResponseEntity.ok(Responses.object(json, clients.usage(id, from, to)));
    }
}
