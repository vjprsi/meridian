package io.meridian.rewards.api;

import io.meridian.rewards.api.generated.RewardRulesApi;
import io.meridian.rewards.api.generated.model.RewardRule;
import io.meridian.rewards.api.generated.model.RewardRuleCreate;
import io.meridian.rewards.api.generated.model.RewardRuleUpdate;
import io.meridian.rewards.api.generated.model.RewardRulesDeactivateRequest;
import io.meridian.rewards.api.generated.model.RewardRulesSimulateRequest;
import io.meridian.rewards.application.EarnService;
import io.meridian.rewards.application.Json;
import io.meridian.rewards.application.ProgramService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Earn rules: validated set-up, activation and side-effect free simulation. */
@RestController
public class RewardRulesController implements RewardRulesApi {
    private final ProgramService programs;
    private final EarnService earn;
    private final Http http;

    public RewardRulesController(ProgramService programs, EarnService earn, Http http) {
        this.programs = programs;
        this.earn = earn;
        this.http = http;
    }

    @Override
    public ResponseEntity<RewardRule> rewardRulesCreate(String idempotencyKey, RewardRuleCreate body) {
        return http.created(idempotencyKey, "rule.create", body, RewardRule.class, () -> programs.createRule(body));
    }

    @Override
    public ResponseEntity<RewardRule> rewardRulesUpdate(UUID id, String ifMatch, RewardRuleUpdate body) {
        return http.withEtag(programs.updateRule(id, body, ifMatch), RewardRule.class);
    }

    @Override
    public ResponseEntity<RewardRule> rewardRulesActivate(UUID id, String idempotencyKey) {
        return http.ok(idempotencyKey, "rule.activate", id, null, RewardRule.class, () -> programs.activateRule(id));
    }

    @Override
    public ResponseEntity<RewardRule> rewardRulesDeactivate(UUID id, String idempotencyKey, RewardRulesDeactivateRequest body) {
        String reason = Json.text(http.tree(body), "reason");
        return http.ok(idempotencyKey, "rule.deactivate", id, body, RewardRule.class, () -> programs.deactivateRule(id, reason));
    }

    @Override
    public ResponseEntity<Map<String, Object>> rewardRulesSimulate(String idempotencyKey, RewardRulesSimulateRequest body) {
        return http.document(idempotencyKey, "rule.simulate", null, body, () -> earn.simulate(http.tree(body)));
    }
}
