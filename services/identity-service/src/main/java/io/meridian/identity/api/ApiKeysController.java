package io.meridian.identity.api;

import io.meridian.identity.api.generated.ApiKeysApi;
import io.meridian.identity.api.generated.model.ApiKey;
import io.meridian.identity.api.generated.model.ApiKeyCreate;
import io.meridian.identity.api.generated.model.ApiKeysRevokeRequest;
import io.meridian.identity.api.generated.model.ApiKeysRotateRequest;
import io.meridian.identity.application.ApiKeyService;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** API keys: plaintext key returned once on create / rotate (response-only {@code api_key}); only a hash is stored. */
@RestController
public class ApiKeysController implements ApiKeysApi {
    private final ApiKeyService keys;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public ApiKeysController(ApiKeyService keys, CrudEngine crud, Idempotency idempotency, JsonMapper json) {
        this.keys = keys;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<ApiKey> apiKeysCreate(UUID apiClientId, String idempotencyKey, ApiKeyCreate body) {
        return Responses.secretOnce(idempotency, json, idempotencyKey,
                Map.of("client", apiClientId, "body", json.valueToTree(body)), HttpStatus.CREATED, ApiKey.class, () -> {
                    ApiKeyService.Issued i = keys.create(apiClientId, body);
                    return new Responses.WithSecrets(i.body(), i.secrets());
                }, ApiKeysController::reveal);
    }

    @Override
    public ResponseEntity<ApiKey> apiKeysRotate(UUID apiClientId, UUID id, String idempotencyKey, ApiKeysRotateRequest body) {
        JsonNode req = body == null ? json.createObjectNode() : json.valueToTree(body);
        return Responses.secretOnce(idempotency, json, idempotencyKey, Map.of("op", "rotate", "id", id, "body", req),
                HttpStatus.OK, ApiKey.class, () -> {
                    ApiKeyService.Issued i = keys.rotate(apiClientId, id, body == null ? null : body.getGracePeriodHours());
                    return new Responses.WithSecrets(i.body(), i.secrets());
                }, ApiKeysController::reveal);
    }

    private static void reveal(ApiKey k, Map<String, String> secrets) {
        k.setApiKey(secrets.get("api_key"));
    }

    @Override
    public ResponseEntity<ApiKey> apiKeysRevoke(UUID apiClientId, UUID id, String idempotencyKey, ApiKeysRevokeRequest body) {
        return idempotency.execute(idempotencyKey, Map.of("op", "revoke", "id", id, "body", json.valueToTree(body)),
                ApiKey.class, () -> ResponseEntity.ok(crud.read(keys.revoke(apiClientId, id, body.getReason()), ApiKey.class)));
    }
}
