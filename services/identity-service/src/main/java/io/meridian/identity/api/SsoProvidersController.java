package io.meridian.identity.api;

import io.meridian.identity.api.generated.SsoProvidersApi;
import io.meridian.identity.application.SsoService;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** SSO providers; {@code /test} runs an offline configuration check. */
@RestController
public class SsoProvidersController implements SsoProvidersApi {
    private final SsoService sso;
    private final Idempotency idempotency;

    public SsoProvidersController(SsoService sso, Idempotency idempotency) {
        this.sso = sso;
        this.idempotency = idempotency;
    }

    @Override
    public ResponseEntity<Void> ssoProvidersTest(UUID id, String idempotencyKey) {
        return Responses.body(idempotency.execute(idempotencyKey, Map.of("op", "sso-test", "id", id), JsonNode.class,
                () -> ResponseEntity.ok(sso.test(id))));
    }
}
