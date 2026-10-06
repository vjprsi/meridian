package io.meridian.rewards.api;

import io.meridian.rewards.api.generated.RewardsProgramsApi;
import io.meridian.rewards.api.generated.model.RewardRulePage;
import io.meridian.rewards.api.generated.model.RewardTierPage;
import io.meridian.rewards.api.generated.model.RewardsProgram;
import io.meridian.rewards.api.generated.model.RewardsProgramCreate;
import io.meridian.rewards.api.generated.model.RewardsProgramUpdate;
import io.meridian.rewards.api.generated.model.RewardsProgramsActivateRequest;
import io.meridian.rewards.api.generated.model.RewardsProgramsEndRequest;
import io.meridian.rewards.api.generated.model.RewardsProgramsPauseRequest;
import io.meridian.rewards.application.Json;
import io.meridian.rewards.application.ProgramService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Rewards programs: maker-checker activation, pause, end, and their tiers and rules. */
@RestController
public class RewardsProgramsController implements RewardsProgramsApi {
    private final ProgramService programs;
    private final Http http;
    private final CrudEngine crud;

    public RewardsProgramsController(ProgramService programs, Http http, CrudEngine crud) {
        this.programs = programs;
        this.http = http;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<RewardsProgram> rewardsProgramsCreate(String idempotencyKey, RewardsProgramCreate body) {
        return http.created(idempotencyKey, "program.create", body, RewardsProgram.class, () -> programs.createProgram(body));
    }

    @Override
    public ResponseEntity<RewardsProgram> rewardsProgramsUpdate(UUID id, String ifMatch, RewardsProgramUpdate body) {
        return http.withEtag(programs.updateProgram(id, body, ifMatch), RewardsProgram.class);
    }

    @Override
    public ResponseEntity<RewardsProgram> rewardsProgramsActivate(UUID id, String idempotencyKey, RewardsProgramsActivateRequest body) {
        String comment = Json.text(http.tree(body), "comment");
        return http.ok(idempotencyKey, "program.activate", id, body, RewardsProgram.class, () -> programs.activateProgram(id, comment));
    }

    @Override
    public ResponseEntity<RewardsProgram> rewardsProgramsPause(UUID id, String idempotencyKey, RewardsProgramsPauseRequest body) {
        String reason = Json.required(http.tree(body), "reason");
        return http.ok(idempotencyKey, "program.pause", id, body, RewardsProgram.class, () -> programs.pauseProgram(id, reason));
    }

    @Override
    public ResponseEntity<RewardsProgram> rewardsProgramsEnd(UUID id, String idempotencyKey, RewardsProgramsEndRequest body) {
        var b = http.tree(body);
        LocalDate endsOn = LocalDate.parse(Json.required(b, "ends_on"));
        String reason = Json.text(b, "reason");
        return http.ok(idempotencyKey, "program.end", id, body, RewardsProgram.class, () -> programs.endProgram(id, endsOn, reason));
    }

    @Override
    public ResponseEntity<RewardTierPage> rewardsProgramsTiers(UUID id, Integer limit, String cursor) {
        crud.get("rewards_program", RewardsProgram.class, Crud.params().with("id", id));
        return crud.list("reward_tier", RewardTierPage.class,
                Crud.params().with("rewards_program_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<RewardRulePage> rewardsProgramsRules(UUID id, Integer limit, String cursor) {
        crud.get("rewards_program", RewardsProgram.class, Crud.params().with("id", id));
        return crud.list("reward_rule", RewardRulePage.class,
                Crud.params().with("rewards_program_id", id).with("limit", limit).with("cursor", cursor));
    }
}
