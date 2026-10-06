package io.meridian.identity.api;

import io.meridian.identity.api.generated.SsoProvidersApi;
import io.meridian.identity.application.SsoService;
import io.meridian.web.idempotency.Idempotency;
import tools.jackson.databind.json.JsonMapper;
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
    private final JsonMapper json;

    public SsoProvidersController(SsoService sso, Idempotency idempotency, JsonMapper json) {
        this.sso = sso;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<Map<String, Object>> ssoProvidersTest(UUID id, String idempotencyKey) {
        return Responses.object(json, idempotency.execute(idempotencyKey, Map.of("op", "sso-test", "id", id),
                JsonNode.class, () -> ResponseEntity.ok(sso.test(id))));
    }
}
