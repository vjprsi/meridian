package io.meridian.liquidity.api;

import io.meridian.liquidity.api.generated.IntradayLiquidityApi;
import io.meridian.liquidity.api.generated.model.IntradayLiquiditySnapshot;
import io.meridian.liquidity.api.generated.model.IntradayLiquiditySnapshotCreate;
import io.meridian.liquidity.application.IntradayService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Intraday liquidity snapshots computed from the day's settled movements (BCBS 248). */
@RestController
public class IntradayLiquidityController implements IntradayLiquidityApi {
    private final IntradayService intraday;
    private final Http http;

    public IntradayLiquidityController(IntradayService intraday, Http http) {
        this.intraday = intraday;
        this.http = http;
    }

    @Override
    public ResponseEntity<IntradayLiquiditySnapshot> intradayLiquidityCreate(String idempotencyKey,
                                                                             IntradayLiquiditySnapshotCreate body) {
        return http.created(idempotencyKey, "intraday-create", body, IntradayLiquiditySnapshot.class,
                () -> intraday.create(body));
    }
}
