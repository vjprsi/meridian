package io.meridian.billing.api;

import io.meridian.billing.api.generated.UsageApi;
import io.meridian.billing.api.generated.model.UsageRecord;
import io.meridian.billing.api.generated.model.UsageRecordCreate;
import io.meridian.billing.application.UsageService;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Usage metering and per-metric summaries. */
@RestController
public class UsageController implements UsageApi {
    private final UsageService usage;
    private final CrudEngine crud;
    private final Idempotency idempotency;

    public UsageController(UsageService usage, CrudEngine crud, Idempotency idempotency) {
        this.usage = usage;
        this.crud = crud;
        this.idempotency = idempotency;
    }

    @Override
    public ResponseEntity<UsageRecord> usageCreate(String idempotencyKey, UsageRecordCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, UsageRecord.class, () -> {
            UsageService.Recorded r = usage.record(body, idempotencyKey, LocalDate.now(ZoneOffset.UTC));
            UsageRecord rec = crud.read(r.row(), UsageRecord.class);
            return r.replayed()
                    ? ResponseEntity.ok().header("Idempotent-Replayed", "true").body(rec)
                    : ResponseEntity.status(HttpStatus.CREATED).body(rec);
        });
    }

    /** Usage totals per metric for [from, to], optionally for one reporting service. */
    @Override
    public ResponseEntity<Map<String, Object>> usageSummary(LocalDate from, LocalDate to, String serviceName) {
        TenantContext.require();
        return ResponseEntity.ok(usage.summary(from, to, serviceName));
    }
}
