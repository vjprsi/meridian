package io.meridian.kyc.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.kyc.api.generated.KycProvidersApi;
import io.meridian.kyc.api.generated.model.KycProvider;
import io.meridian.kyc.api.generated.model.KycProviderCreate;
import io.meridian.kyc.api.generated.model.KycProviderUpdate;
import io.meridian.kyc.application.KycSupport;
import io.meridian.kyc.application.ProviderService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

/** KYC providers: validated configuration (secrets by vault reference only) and connectivity tests. */
@RestController
public class KycProvidersController implements KycProvidersApi {
    private final ProviderService providers;
    private final Actions actions;
    private final KycSupport kyc;

    public KycProvidersController(ProviderService providers, Actions actions, KycSupport kyc) {
        this.providers = providers;
        this.actions = actions;
        this.kyc = kyc;
    }

    @Override
    public ResponseEntity<KycProvider> kycProvidersCreate(String idempotencyKey, KycProviderCreate body) {
        return actions.created(idempotencyKey, "provider.create", body, KycProvider.class, () -> providers.create(body));
    }

    @Override
    public ResponseEntity<KycProvider> kycProvidersUpdate(UUID id, String ifMatch, KycProviderUpdate body) {
        TenantContext.require();
        ObjectNode row = providers.update(id, body, ifMatch);
        return ResponseEntity.ok().eTag("\"" + row.get("row_version").asString() + "\"").body(kyc.read(row, KycProvider.class));
    }

    /** Result of the connectivity test (ok, latency, message). */
    @Override
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> kycProvidersTestConnection(UUID id, String idempotencyKey) {
        return actions.raw(idempotencyKey, "provider.test-connection", id, null,
                (Class<Map<String, Object>>) (Class<?>) Map.class, () -> ResponseEntity.ok(providers.testConnection(id)));
    }
}
