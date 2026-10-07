package io.meridian.merchantchannel.api;

import io.meridian.merchantchannel.api.generated.MerchantAppApiClientsApi;
import io.meridian.merchantchannel.api.generated.model.ApiClientPage;
import io.meridian.merchantchannel.api.generated.model.ApiKey;
import io.meridian.merchantchannel.api.generated.model.ApiKeyPage;
import io.meridian.merchantchannel.api.generated.model.MerchantAppApiClientsAddKeysRequest;
import io.meridian.merchantchannel.api.generated.model.MerchantAppApiClientsCreateRequest;
import io.meridian.merchantchannel.api.generated.model.MerchantAppApiClientsKeysRevokeRequest;
import io.meridian.merchantchannel.api.generated.model.MerchantAppApiClientsSuspendRequest;
import io.meridian.merchantchannel.api.generated.model.ModelApiClient;
import io.meridian.merchantchannel.application.IntegrationService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** API clients and keys of the merchant (OWNER). */
@RestController
public class MerchantAppApiClientsController implements MerchantAppApiClientsApi {
    private final IntegrationService integrations;
    private final Http http;

    public MerchantAppApiClientsController(IntegrationService integrations, Http http) {
        this.integrations = integrations;
        this.http = http;
    }

    @Override
    public ResponseEntity<ApiClientPage> merchantAppApiClientsList(Integer limit, String cursor) {
        return http.ok(integrations.clients(), ApiClientPage.class);
    }

    @Override
    public ResponseEntity<ModelApiClient> merchantAppApiClientsCreate(String idempotencyKey,
                                                                   MerchantAppApiClientsCreateRequest body) {
        return http.ok(integrations.createClient(http.body(body), idempotencyKey), ModelApiClient.class);
    }

    @Override
    public ResponseEntity<ModelApiClient> merchantAppApiClientsGet(UUID clientId) {
        return http.ok(integrations.client(clientId), ModelApiClient.class);
    }

    @Override
    public ResponseEntity<ModelApiClient> merchantAppApiClientsSuspend(UUID clientId, String idempotencyKey,
                                                                    MerchantAppApiClientsSuspendRequest body) {
        return http.ok(integrations.suspendClient(clientId, http.body(body), idempotencyKey), ModelApiClient.class);
    }

    @Override
    public ResponseEntity<ApiKeyPage> merchantAppApiClientsKeys(UUID clientId, Integer limit, String cursor) {
        return http.ok(integrations.keys(clientId, limit, cursor), ApiKeyPage.class);
    }

    @Override
    public ResponseEntity<ApiKey> merchantAppApiClientsAddKeys(UUID clientId, String idempotencyKey,
                                                            MerchantAppApiClientsAddKeysRequest body) {
        return http.ok(integrations.createKey(clientId, http.body(body), idempotencyKey), ApiKey.class);
    }

    @Override
    public ResponseEntity<ApiKey> merchantAppApiClientsKeysRevoke(UUID clientId, UUID keyId, String idempotencyKey,
                                                               MerchantAppApiClientsKeysRevokeRequest body) {
        return http.ok(integrations.revokeKey(clientId, keyId, http.body(body), idempotencyKey), ApiKey.class);
    }
}
