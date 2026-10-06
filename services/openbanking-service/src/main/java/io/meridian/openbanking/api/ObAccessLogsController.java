package io.meridian.openbanking.api;

import io.meridian.openbanking.api.generated.ObAccessLogsApi;
import io.meridian.openbanking.api.generated.model.ObAccessLog;
import io.meridian.openbanking.api.generated.model.ObAccessLogCreate;
import io.meridian.openbanking.application.AccessLogService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Append-only TPP access log: gateway ingestion (validated) and queries. */
@RestController
public class ObAccessLogsController implements ObAccessLogsApi {
    private final AccessLogService service;
    private final Actions actions;

    public ObAccessLogsController(AccessLogService service, Actions actions) {
        this.service = service;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<ObAccessLog> obAccessLogsCreate(String idempotencyKey, ObAccessLogCreate body) {
        return actions.create(idempotencyKey, body, ObAccessLog.class, () -> service.create(body));
    }
}
