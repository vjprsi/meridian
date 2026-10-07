package io.meridian.reconciliation.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.reconciliation.api.generated.ReconRunsApi;
import io.meridian.reconciliation.api.generated.model.ReconRun;
import io.meridian.reconciliation.api.generated.model.ReconRunCreate;
import io.meridian.reconciliation.application.EodService;
import io.meridian.reconciliation.application.MetricsService;
import io.meridian.reconciliation.application.RunService;
import io.meridian.web.idempotency.Idempotency;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Creating a run executes the matching synchronously; the response carries the final status, counts and metrics.
 * {@code POST /eod} reconciles every linked source for a business date (EOD job); {@code GET /metrics} reports
 * auto-match rates and break ageing.
 */
@RestController
public class ReconRunsController implements ReconRunsApi {
    private final RunService runs;
    private final EodService eod;
    private final MetricsService metrics;
    private final Idempotency idempotency;
    private final Responses responses;
    private final JsonMapper json;

    public ReconRunsController(RunService runs, EodService eod, MetricsService metrics, Idempotency idempotency,
                               Responses responses, JsonMapper json) {
        this.runs = runs;
        this.eod = eod;
        this.metrics = metrics;
        this.idempotency = idempotency;
        this.responses = responses;
        this.json = json;
    }

    @Override
    public ResponseEntity<ReconRun> reconRunsCreate(String idempotencyKey, ReconRunCreate body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        return idempotency.execute(idempotencyKey, body, ReconRun.class,
                () -> responses.created(runs.create(req), ReconRun.class));
    }

    @Override
    public ResponseEntity<Map<String, Object>> reconRunsEod(LocalDate businessDate, String idempotencyKey) {
        TenantContext.require();
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("action", "recon-eod");
        fingerprint.put("business_date", String.valueOf(businessDate));
        return responses.objectAction(idempotency, idempotencyKey, fingerprint, () -> eod.endOfDay(businessDate));
    }

    @Override
    public ResponseEntity<Map<String, Object>> reconRunsMetrics(LocalDate fromDate, LocalDate toDate, UUID reconSourceId) {
        TenantContext.require();
        return ResponseEntity.ok(metrics.metrics(fromDate, toDate, reconSourceId));
    }
}
