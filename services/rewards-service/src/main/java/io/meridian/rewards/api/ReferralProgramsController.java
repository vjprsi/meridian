package io.meridian.rewards.api;

import io.meridian.rewards.api.generated.ReferralProgramsApi;
import io.meridian.rewards.api.generated.model.ReferralProgram;
import io.meridian.rewards.api.generated.model.ReferralProgramCreate;
import io.meridian.rewards.api.generated.model.ReferralProgramUpdate;
import io.meridian.rewards.api.generated.model.ReferralProgramsEndRequest;
import io.meridian.rewards.api.generated.model.ReferralProgramsPauseRequest;
import io.meridian.rewards.application.Json;
import io.meridian.rewards.application.ReferralService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Referral programs and their lifecycle. */
@RestController
public class ReferralProgramsController implements ReferralProgramsApi {
    private final ReferralService referrals;
    private final Http http;

    public ReferralProgramsController(ReferralService referrals, Http http) {
        this.referrals = referrals;
        this.http = http;
    }

    @Override
    public ResponseEntity<ReferralProgram> referralProgramsCreate(String idempotencyKey, ReferralProgramCreate body) {
        return http.created(idempotencyKey, "referral-program.create", body, ReferralProgram.class, () -> referrals.createProgram(body));
    }

    @Override
    public ResponseEntity<ReferralProgram> referralProgramsUpdate(UUID id, String ifMatch, ReferralProgramUpdate body) {
        return http.withEtag(referrals.updateProgram(id, body, ifMatch), ReferralProgram.class);
    }

    @Override
    public ResponseEntity<ReferralProgram> referralProgramsActivate(UUID id, String idempotencyKey) {
        return http.ok(idempotencyKey, "referral-program.activate", id, null, ReferralProgram.class, () -> referrals.activateProgram(id));
    }

    @Override
    public ResponseEntity<ReferralProgram> referralProgramsPause(UUID id, String idempotencyKey, ReferralProgramsPauseRequest body) {
        String reason = Json.required(http.tree(body), "reason");
        return http.ok(idempotencyKey, "referral-program.pause", id, body, ReferralProgram.class, () -> referrals.pauseProgram(id, reason));
    }

    @Override
    public ResponseEntity<ReferralProgram> referralProgramsEnd(UUID id, String idempotencyKey, ReferralProgramsEndRequest body) {
        String reason = Json.text(http.tree(body), "reason");
        return http.ok(idempotencyKey, "referral-program.end", id, body, ReferralProgram.class, () -> referrals.endProgram(id, reason));
    }
}
