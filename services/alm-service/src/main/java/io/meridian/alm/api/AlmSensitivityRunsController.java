package io.meridian.alm.api;

import io.meridian.alm.api.generated.AlmSensitivityRunsApi;
import io.meridian.alm.api.generated.model.AlmSensitivityRun;
import io.meridian.alm.api.generated.model.AlmSensitivityRunCreate;
import io.meridian.alm.api.generated.model.AlmSensitivityRunsStandardShocksRequest;
import io.meridian.alm.application.SensitivityService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** NII / EVE sensitivity runs and the Basel standard-shock batch. */
@RestController
public class AlmSensitivityRunsController implements AlmSensitivityRunsApi {
    private final SensitivityService service;
    private final Actions actions;

    public AlmSensitivityRunsController(SensitivityService service, Actions actions) {
        this.service = service;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<AlmSensitivityRun> almSensitivityRunsCreate(String idempotencyKey, AlmSensitivityRunCreate body) {
        return actions.create(idempotencyKey, body, AlmSensitivityRun.class,
                () -> service.create(body, actions.tree(body)));
    }

    @Override
    public ResponseEntity<Map<String, Object>> almSensitivityRunsStandardShocks(
            String idempotencyKey, AlmSensitivityRunsStandardShocksRequest body) {
        return actions.objectAction(idempotencyKey, "standard-shocks", body,
                () -> service.standardShocks(actions.tree(body)));
    }
}
