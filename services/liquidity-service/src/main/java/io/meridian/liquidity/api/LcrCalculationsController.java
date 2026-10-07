package io.meridian.liquidity.api;

import io.meridian.liquidity.api.generated.LcrCalculationsApi;
import io.meridian.liquidity.api.generated.model.LcrCalculation;
import io.meridian.liquidity.api.generated.model.LcrCalculationsApproveRequest;
import io.meridian.liquidity.api.generated.model.LcrCalculationsCalculateRequest;
import io.meridian.liquidity.api.generated.model.LcrCalculationsRejectRequest;
import io.meridian.liquidity.application.RatioService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Basel III LCR: calculate (upsert per entity, date and currency) and four-eyes approval. */
@RestController
public class LcrCalculationsController implements LcrCalculationsApi {
    private final RatioService ratios;
    private final Http http;

    public LcrCalculationsController(RatioService ratios, Http http) {
        this.ratios = ratios;
        this.http = http;
    }

    @Override
    public ResponseEntity<LcrCalculation> lcrCalculationsCalculate(String idempotencyKey,
                                                                   LcrCalculationsCalculateRequest body) {
        return http.ok(idempotencyKey, "lcr-calculate", null, body, LcrCalculation.class,
                () -> ratios.calculateLcr(http.tree(body)));
    }

    @Override
    public ResponseEntity<LcrCalculation> lcrCalculationsApprove(UUID id, String idempotencyKey,
                                                                 LcrCalculationsApproveRequest body) {
        return http.ok(idempotencyKey, "lcr-approve", id, body, LcrCalculation.class,
                () -> ratios.approve("lcr_calculation", id));
    }

    @Override
    public ResponseEntity<LcrCalculation> lcrCalculationsReject(UUID id, String idempotencyKey,
                                                                LcrCalculationsRejectRequest body) {
        return http.ok(idempotencyKey, "lcr-reject", id, body, LcrCalculation.class,
                () -> ratios.reject("lcr_calculation", id, http.tree(body).path("reason").asString(null)));
    }
}
