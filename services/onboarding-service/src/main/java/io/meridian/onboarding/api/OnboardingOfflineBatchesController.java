package io.meridian.onboarding.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.onboarding.api.generated.OnboardingOfflineBatchesApi;
import io.meridian.onboarding.api.generated.model.OnboardingOfflineBatch;
import io.meridian.onboarding.api.generated.model.OnboardingOfflineBatchCreate;
import io.meridian.onboarding.application.OfflineBatchService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Offline batches captured by agent devices: synced on receipt, reprocessable after conflicts are resolved. */
@RestController
public class OnboardingOfflineBatchesController implements OnboardingOfflineBatchesApi {
    private final OfflineBatchService batches;
    private final ActionRunner actions;

    public OnboardingOfflineBatchesController(OfflineBatchService batches, ActionRunner actions) {
        this.batches = batches;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<OnboardingOfflineBatch> onboardingOfflineBatchesCreate(String idempotencyKey,
                                                                               OnboardingOfflineBatchCreate body) {
        TenantContext.require();
        return actions.run(idempotencyKey, "create", null, body, OnboardingOfflineBatch.class, HttpStatus.CREATED,
                () -> batches.create(body, idempotencyKey));
    }

    @Override
    public ResponseEntity<OnboardingOfflineBatch> onboardingOfflineBatchesReprocess(UUID id, String idempotencyKey) {
        TenantContext.require();
        return actions.run(idempotencyKey, "reprocess", id, null, OnboardingOfflineBatch.class, HttpStatus.OK,
                () -> batches.reprocess(id));
    }
}
