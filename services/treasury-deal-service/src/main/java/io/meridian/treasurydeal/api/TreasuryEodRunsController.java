package io.meridian.treasurydeal.api;

import io.meridian.treasurydeal.api.generated.TreasuryEodRunsApi;
import io.meridian.treasurydeal.application.EndOfDayJobs;
import io.meridian.treasurydeal.application.EndOfDayJobs.Job;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Treasury end-of-day jobs (called by business-day-service per tenant and business date; idempotent per date) and
 * the run ledger (list / get through the generic CRUD defaults).
 */
@RestController
public class TreasuryEodRunsController implements TreasuryEodRunsApi {
    private final EndOfDayJobs eod;
    private final Actions actions;

    public TreasuryEodRunsController(EndOfDayJobs eod, Actions actions) {
        this.eod = eod;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<Map<String, Object>> treasuryEodRunsMarkToMarket(LocalDate businessDate, String idempotencyKey) {
        return run(Job.MARK_TO_MARKET, businessDate, idempotencyKey);
    }

    @Override
    public ResponseEntity<Map<String, Object>> treasuryEodRunsAccruals(LocalDate businessDate, String idempotencyKey) {
        return run(Job.INTEREST_ACCRUAL, businessDate, idempotencyKey);
    }

    @Override
    public ResponseEntity<Map<String, Object>> treasuryEodRunsMaturities(LocalDate businessDate, String idempotencyKey) {
        return run(Job.MATURITY_PROCESSING, businessDate, idempotencyKey);
    }

    @Override
    public ResponseEntity<Map<String, Object>> treasuryEodRunsLimitUtilisation(LocalDate businessDate, String idempotencyKey) {
        return run(Job.LIMIT_UTILISATION_REFRESH, businessDate, idempotencyKey);
    }

    private ResponseEntity<Map<String, Object>> run(Job job, LocalDate businessDate, String key) {
        return actions.objectAction(key, "eod-" + job.name(), Map.of("business_date", String.valueOf(businessDate)),
                () -> eod.run(job, businessDate));
    }
}
