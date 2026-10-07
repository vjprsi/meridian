package io.meridian.agentchannel.api;

import io.meridian.agentchannel.api.generated.AgentMeOfflineBatchesApi;
import io.meridian.agentchannel.api.generated.model.AgentMeOfflineBatchesCreateRequest;
import io.meridian.agentchannel.api.generated.model.OnboardingOfflineBatch;
import io.meridian.agentchannel.api.generated.model.OnboardingOfflineBatchPage;
import io.meridian.agentchannel.application.OnboardingService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Onboarding batches captured offline, verified against the device key. */
@RestController
public class AgentMeOfflineBatchesController implements AgentMeOfflineBatchesApi {
    private final OnboardingService onboarding;
    private final Http http;

    public AgentMeOfflineBatchesController(OnboardingService onboarding, Http http) {
        this.onboarding = onboarding;
        this.http = http;
    }

    @Override
    public ResponseEntity<OnboardingOfflineBatchPage> agentMeOfflineBatchesList(String idempotencyKey, Integer limit,
                                                                                String cursor) {
        return http.ok(onboarding.batches(idempotencyKey, limit, cursor), OnboardingOfflineBatchPage.class);
    }

    @Override
    public ResponseEntity<OnboardingOfflineBatch> agentMeOfflineBatchesCreate(String idempotencyKey,
                                                                             AgentMeOfflineBatchesCreateRequest body) {
        return http.ok(onboarding.submitBatch(http.body(body), idempotencyKey), OnboardingOfflineBatch.class);
    }

    @Override
    public ResponseEntity<OnboardingOfflineBatch> agentMeOfflineBatchesGet(UUID batchId) {
        return http.ok(onboarding.batch(batchId), OnboardingOfflineBatch.class);
    }
}
