package io.meridian.kyc.api;

import io.meridian.kyc.api.generated.KycProvidersApi;
import io.meridian.kyc.api.generated.model.KycProvider;
import io.meridian.kyc.api.generated.model.KycProviderCreate;
import io.meridian.kyc.api.generated.model.KycProviderUpdate;
import io.meridian.kyc.application.KycSupport;
import io.meridian.kyc.application.ProviderService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** KYC providers: validated configuration and connectivity tests. Contract: kyc-service.openapi.yaml */
@RestController
public class KycProvidersController implements KycProvidersApi {
    private final ProviderService providers;
    private final Actions actions;
    private final CrudEngine crud;
    private final KycSupport kyc;

    public KycProvidersController(ProviderService providers, Actions actions, CrudEngine crud, KycSupport kyc) {
        this.providers = providers;
        this.actions = actions;
        this.crud = crud;
        this.kyc = kyc;
    }

    @Override
    public ResponseEntity<KycProvider> kycProvidersCreate(String idempotencyKey, KycProviderCreate body) {
        return actions.created(idempotencyKey, "provider.create", body, KycProvider.class, () -> providers.create(body));
    }

    @Override
    public ResponseEntity<KycProvider> kycProvidersUpdate(UUID id, String ifMatch, KycProviderUpdate body) {
        providers.validateUpdate(body);
        JsonNode normalised = kyc.tree(body);
        if (normalised.has("capabilities") || normalised.has("countries")) {
            // store list-valued json in the canonical {"values": [...]} shape
            var v = crud.toColumns("kyc_provider", body);
            if (v.has("capabilities")) {
                body.setCapabilities(asMap(kyc.valuesObject(KycSupport.values(v.get("capabilities")).stream()
                        .map(s -> s.toUpperCase(java.util.Locale.ROOT)).distinct().toList())));
            }
            if (v.has("countries")) {
                body.setCountries(asMap(kyc.valuesObject(KycSupport.values(v.get("countries")).stream()
                        .map(s -> s.toUpperCase(java.util.Locale.ROOT)).distinct().toList())));
            }
        }
        return crud.update("kyc_provider", KycProvider.class, body, ifMatch, Crud.params().with("id", id));
    }

    /** Contract declares no body (defect); the result of the test is returned as JSON with 200. */
    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public ResponseEntity kycProvidersTestConnection(UUID id, String idempotencyKey) {
        return actions.raw(idempotencyKey, "provider.test-connection", id, null, JsonNode.class,
                () -> ResponseEntity.ok(kyc.tree(providers.testConnection(id))));
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> asMap(JsonNode n) {
        return kyc.read(n, java.util.Map.class);
    }
}
