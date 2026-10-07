package io.meridian.transactionmonitoring.api;

import io.meridian.transactionmonitoring.api.generated.TmScenarioVersionsApi;
import io.meridian.transactionmonitoring.api.generated.model.TmScenarioVersion;
import io.meridian.transactionmonitoring.api.generated.model.TmScenarioVersionsRejectRequest;
import io.meridian.transactionmonitoring.api.generated.model.TmScenarioVersionsSubmitRequest;
import io.meridian.transactionmonitoring.application.ScenarioService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Scenario threshold versions: list/get (generated CRUD) and maker-checker activation submissions. */
@RestController
public class TmScenarioVersionsController implements TmScenarioVersionsApi {
    private final ScenarioService scenarios;
    private final Http http;

    public TmScenarioVersionsController(ScenarioService scenarios, Http http) {
        this.scenarios = scenarios;
        this.http = http;
    }

    @Override
    public ResponseEntity<TmScenarioVersion> tmScenarioVersionsSubmit(UUID id, String idempotencyKey,
                                                                      TmScenarioVersionsSubmitRequest body) {
        String comment = Http.text(http.tree(body), "comment");
        return http.once(idempotencyKey, body, TmScenarioVersion.class,
                () -> http.ok(scenarios.submitVersion(id, comment), TmScenarioVersion.class));
    }

    @Override
    public ResponseEntity<TmScenarioVersion> tmScenarioVersionsReject(UUID id, String idempotencyKey,
                                                                      TmScenarioVersionsRejectRequest body) {
        String reason = Http.text(http.tree(body), "reason");
        return http.once(idempotencyKey, body, TmScenarioVersion.class,
                () -> http.ok(scenarios.rejectVersion(id, reason), TmScenarioVersion.class));
    }
}
