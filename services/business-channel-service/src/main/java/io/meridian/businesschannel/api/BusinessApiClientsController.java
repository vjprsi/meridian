package io.meridian.businesschannel.api;

import io.meridian.businesschannel.api.generated.BusinessApiClientsApi;
import io.meridian.businesschannel.api.generated.model.ApiClientPage;
import io.meridian.businesschannel.api.generated.model.ApiKey;
import io.meridian.businesschannel.api.generated.model.ApiKeyPage;
import io.meridian.businesschannel.api.generated.model.BusinessApiClientsAddKeysRequest;
import io.meridian.businesschannel.api.generated.model.BusinessApiClientsCreateRequest;
import io.meridian.businesschannel.api.generated.model.BusinessApiClientsKeysRevokeRequest;
import io.meridian.businesschannel.api.generated.model.BusinessApiClientsSuspendRequest;
import io.meridian.businesschannel.api.generated.model.ModelApiClient;
import io.meridian.businesschannel.application.IntegrationService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** API clients and keys of the business (ADMIN). */
@RestController
public class BusinessApiClientsController implements BusinessApiClientsApi {
    private final IntegrationService integrations;
    private final Http http;

    public BusinessApiClientsController(IntegrationService integrations, Http http) {
        this.integrations = integrations;
        this.http = http;
    }

    @Override
    public ResponseEntity<ApiClientPage> businessApiClientsList(Integer limit, String cursor) {
        return http.ok(integrations.clients(), ApiClientPage.class);
    }

    @Override
    public ResponseEntity<ModelApiClient> businessApiClientsCreate(String idempotencyKey,
                                                                   BusinessApiClientsCreateRequest body) {
        return http.ok(integrations.createClient(http.body(body), idempotencyKey), ModelApiClient.class);
    }

    @Override
    public ResponseEntity<ModelApiClient> businessApiClientsGet(UUID clientId) {
        return http.ok(integrations.client(clientId), ModelApiClient.class);
    }

    @Override
    public ResponseEntity<ModelApiClient> businessApiClientsSuspend(UUID clientId, String idempotencyKey,
                                                                    BusinessApiClientsSuspendRequest body) {
        return http.ok(integrations.suspendClient(clientId, http.body(body), idempotencyKey), ModelApiClient.class);
    }

    @Override
    public ResponseEntity<ApiKeyPage> businessApiClientsKeys(UUID clientId, Integer limit, String cursor) {
        return http.ok(integrations.keys(clientId, limit, cursor), ApiKeyPage.class);
    }

    @Override
    public ResponseEntity<ApiKey> businessApiClientsAddKeys(UUID clientId, String idempotencyKey,
                                                            BusinessApiClientsAddKeysRequest body) {
        return http.ok(integrations.createKey(clientId, http.body(body), idempotencyKey), ApiKey.class);
    }

    @Override
    public ResponseEntity<ApiKey> businessApiClientsKeysRevoke(UUID clientId, UUID keyId, String idempotencyKey,
                                                               BusinessApiClientsKeysRevokeRequest body) {
        return http.ok(integrations.revokeKey(clientId, keyId, http.body(body), idempotencyKey), ApiKey.class);
    }
}
