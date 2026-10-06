package io.meridian.rewards.api;

import io.meridian.rewards.api.generated.RewardTiersApi;
import io.meridian.rewards.api.generated.model.RewardTier;
import io.meridian.rewards.api.generated.model.RewardTierCreate;
import io.meridian.rewards.api.generated.model.RewardTierUpdate;
import io.meridian.rewards.application.ProgramService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Reward tiers with qualification thresholds; a tier held by members cannot be deleted. */
@RestController
public class RewardTiersController implements RewardTiersApi {
    private final ProgramService programs;
    private final Http http;

    public RewardTiersController(ProgramService programs, Http http) {
        this.programs = programs;
        this.http = http;
    }

    @Override
    public ResponseEntity<RewardTier> rewardTiersCreate(String idempotencyKey, RewardTierCreate body) {
        return http.created(idempotencyKey, "tier.create", body, RewardTier.class, () -> programs.createTier(body));
    }

    @Override
    public ResponseEntity<RewardTier> rewardTiersUpdate(UUID id, String ifMatch, RewardTierUpdate body) {
        return http.withEtag(programs.updateTier(id, body, ifMatch), RewardTier.class);
    }

    @Override
    public ResponseEntity<Void> rewardTiersDelete(UUID id) {
        programs.deleteTier(id);
        return ResponseEntity.noContent().build();
    }
}
