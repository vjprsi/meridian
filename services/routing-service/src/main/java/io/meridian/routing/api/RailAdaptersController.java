package io.meridian.routing.api;

import io.meridian.routing.api.generated.RailAdaptersApi;
import io.meridian.routing.api.generated.model.RailAdapter;
import io.meridian.routing.api.generated.model.RailAdaptersRotateCredentialsRequest;
import io.meridian.routing.application.AdapterService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Rail adapters: connection test and credential rotation. Contract: routing-service.openapi.yaml */
@RestController
public class RailAdaptersController implements RailAdaptersApi {
    private final AdapterService adapters;
    private final Actions actions;

    public RailAdaptersController(AdapterService adapters, Actions actions) {
        this.adapters = adapters;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<Map<String, Object>> railAdaptersTestConnection(UUID id, String idempotencyKey) {
        return actions.object(idempotencyKey, id, "test-connection", null, () -> adapters.testConnection(id));
    }

    @Override
    public ResponseEntity<RailAdapter> railAdaptersRotateCredentials(UUID id, String idempotencyKey,
                                                                     RailAdaptersRotateCredentialsRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "rotate-credentials", body, RailAdapter.class,
                () -> adapters.rotate(id, Http.text(req, "credentials_ref"), Http.text(req, "reason")));
    }
}
