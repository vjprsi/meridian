package io.meridian.liquidity.api;

import io.meridian.liquidity.api.generated.CashPositionsApi;
import io.meridian.liquidity.api.generated.model.CashPositionsProjectRequest;
import io.meridian.liquidity.api.generated.model.LiquidityCashPosition;
import io.meridian.liquidity.api.generated.model.LiquidityCashPositionCreate;
import io.meridian.liquidity.application.PositionService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Cash positions: recorded ACTUAL / FORECAST positions and PROJECTED positions from expected flows. */
@RestController
public class CashPositionsController implements CashPositionsApi {
    private final PositionService positions;
    private final Http http;

    public CashPositionsController(PositionService positions, Http http) {
        this.positions = positions;
        this.http = http;
    }

    @Override
    public ResponseEntity<LiquidityCashPosition> cashPositionsCreate(String idempotencyKey,
                                                                     LiquidityCashPositionCreate body) {
        return http.created(idempotencyKey, "cash-position-create", body, LiquidityCashPosition.class,
                () -> positions.create(body));
    }

    @Override
    public ResponseEntity<Map<String, Object>> cashPositionsProject(String idempotencyKey, CashPositionsProjectRequest body) {
        return http.document(idempotencyKey, "cash-position-project", null, body, () -> positions.project(http.tree(body)));
    }
}
