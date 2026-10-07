package io.meridian.liquidity.api;

import io.meridian.liquidity.api.generated.NsfrCalculationsApi;
import io.meridian.liquidity.api.generated.model.NsfrCalculation;
import io.meridian.liquidity.api.generated.model.NsfrCalculationsApproveRequest;
import io.meridian.liquidity.api.generated.model.NsfrCalculationsCalculateRequest;
import io.meridian.liquidity.api.generated.model.NsfrCalculationsRejectRequest;
import io.meridian.liquidity.application.RatioService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Basel III NSFR: calculate (upsert per entity, date and currency) and four-eyes approval. */
@RestController
public class NsfrCalculationsController implements NsfrCalculationsApi {
    private final RatioService ratios;
    private final Http http;

    public NsfrCalculationsController(RatioService ratios, Http http) {
        this.ratios = ratios;
        this.http = http;
    }

    @Override
    public ResponseEntity<NsfrCalculation> nsfrCalculationsCalculate(String idempotencyKey,
                                                                     NsfrCalculationsCalculateRequest body) {
        return http.ok(idempotencyKey, "nsfr-calculate", null, body, NsfrCalculation.class,
                () -> ratios.calculateNsfr(http.tree(body)));
    }

    @Override
    public ResponseEntity<NsfrCalculation> nsfrCalculationsApprove(UUID id, String idempotencyKey,
                                                                   NsfrCalculationsApproveRequest body) {
        return http.ok(idempotencyKey, "nsfr-approve", id, body, NsfrCalculation.class,
                () -> ratios.approve("nsfr_calculation", id));
    }

    @Override
    public ResponseEntity<NsfrCalculation> nsfrCalculationsReject(UUID id, String idempotencyKey,
                                                                  NsfrCalculationsRejectRequest body) {
        return http.ok(idempotencyKey, "nsfr-reject", id, body, NsfrCalculation.class,
                () -> ratios.reject("nsfr_calculation", id, http.tree(body).path("reason").asString(null)));
    }
}
