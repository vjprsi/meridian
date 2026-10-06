package io.meridian.liquidity.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.liquidity.api.generated.LiquidityStressScenariosApi;
import io.meridian.liquidity.api.generated.model.LiquidityStressResult;
import io.meridian.liquidity.api.generated.model.LiquidityStressScenario;
import io.meridian.liquidity.api.generated.model.LiquidityStressScenarioCreate;
import io.meridian.liquidity.api.generated.model.LiquidityStressScenarioUpdate;
import io.meridian.liquidity.api.generated.model.LiquidityStressScenariosApproveRequest;
import io.meridian.liquidity.api.generated.model.LiquidityStressScenariosRunRequest;
import io.meridian.liquidity.application.StressService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Stress scenarios: validated definitions, maker-checker approval, runs over position lines. */
@RestController
public class LiquidityStressScenariosController implements LiquidityStressScenariosApi {
    private final StressService stress;
    private final Http http;

    public LiquidityStressScenariosController(StressService stress, Http http) {
        this.stress = stress;
        this.http = http;
    }

    @Override
    public ResponseEntity<LiquidityStressScenario> liquidityStressScenariosCreate(String idempotencyKey,
                                                                                  LiquidityStressScenarioCreate body) {
        return http.created(idempotencyKey, "stress-scenario-create", body, LiquidityStressScenario.class,
                () -> stress.create(body));
    }

    @Override
    public ResponseEntity<LiquidityStressScenario> liquidityStressScenariosUpdate(UUID id, String ifMatch,
                                                                                  LiquidityStressScenarioUpdate body) {
        TenantContext.require();
        return http.withEtag(stress.update(id, Http.version(ifMatch), body), LiquidityStressScenario.class);
    }

    @Override
    public ResponseEntity<LiquidityStressScenario> liquidityStressScenariosApprove(UUID id, String idempotencyKey,
                                                                                   LiquidityStressScenariosApproveRequest body) {
        return http.ok(idempotencyKey, "stress-scenario-approve", id, body, LiquidityStressScenario.class,
                () -> stress.approve(id));
    }

    @Override
    public ResponseEntity<LiquidityStressResult> liquidityStressScenariosRun(UUID id, String idempotencyKey,
                                                                             LiquidityStressScenariosRunRequest body) {
        return http.ok(idempotencyKey, "stress-scenario-run", id, body, LiquidityStressResult.class,
                () -> stress.run(id, http.tree(body)));
    }
}
