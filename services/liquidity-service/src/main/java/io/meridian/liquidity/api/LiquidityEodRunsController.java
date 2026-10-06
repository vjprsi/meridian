package io.meridian.liquidity.api;

import io.meridian.liquidity.api.generated.LiquidityEodRunsApi;
import io.meridian.liquidity.application.EndOfDayJobs;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** End-of-day liquidity jobs (called by business-day-service) and their run ledger (list / get via CRUD defaults). */
@RestController
public class LiquidityEodRunsController implements LiquidityEodRunsApi {
    private final EndOfDayJobs eod;
    private final Http http;

    public LiquidityEodRunsController(EndOfDayJobs eod, Http http) {
        this.eod = eod;
        this.http = http;
    }

    @Override
    public ResponseEntity<Map<String, Object>> liquidityEodRunsPositionSnapshot(LocalDate businessDate,
                                                                                String idempotencyKey) {
        return http.document(idempotencyKey, "eod-position-snapshot", null,
                Map.of("business_date", String.valueOf(businessDate)),
                () -> eod.run(EndOfDayJobs.Job.LIQUIDITY_POSITION_SNAPSHOT, businessDate));
    }
}
