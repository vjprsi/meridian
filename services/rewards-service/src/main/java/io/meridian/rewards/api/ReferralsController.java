package io.meridian.rewards.api;

import io.meridian.rewards.api.generated.ReferralsApi;
import io.meridian.rewards.api.generated.model.Referral;
import io.meridian.rewards.api.generated.model.ReferralCreate;
import io.meridian.rewards.api.generated.model.ReferralsCodeRequest;
import io.meridian.rewards.api.generated.model.ReferralsQualificationEventsRequest;
import io.meridian.rewards.api.generated.model.ReferralsQualifyRequest;
import io.meridian.rewards.api.generated.model.ReferralsRejectRequest;
import io.meridian.rewards.api.generated.model.ReferralsSignUpRequest;
import io.meridian.rewards.application.Json;
import io.meridian.rewards.application.ReferralService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Referrals: codes, invitations, sign-up with fraud guards, qualification and payouts. */
@RestController
public class ReferralsController implements ReferralsApi {
    private final ReferralService referrals;
    private final Http http;

    public ReferralsController(ReferralService referrals, Http http) {
        this.referrals = referrals;
        this.http = http;
    }

    @Override
    public ResponseEntity<Referral> referralsCreate(String idempotencyKey, ReferralCreate body) {
        return http.created(idempotencyKey, "referral.invite", body, Referral.class, () -> referrals.invite(body));
    }

    @Override
    public ResponseEntity<Referral> referralsCode(String idempotencyKey, ReferralsCodeRequest body) {
        var b = http.tree(body);
        return http.ok(idempotencyKey, "referral.code", null, body, Referral.class, () -> referrals.code(
                Json.uuid(b, "referral_program_id"), Json.uuid(b, "customer_id"), Json.text(b, "device_fingerprint")));
    }

    @Override
    public ResponseEntity<Referral> referralsSignUp(String idempotencyKey, ReferralsSignUpRequest body) {
        return http.ok(idempotencyKey, "referral.sign-up", null, body, Referral.class, () -> referrals.signUp(http.tree(body)));
    }

    @Override
    public ResponseEntity<Map<String, Object>> referralsQualificationEvents(String idempotencyKey,
                                                                            ReferralsQualificationEventsRequest body) {
        return http.document(idempotencyKey, "referral.event", null, body, () -> referrals.qualificationEvent(http.tree(body)));
    }

    @Override
    public ResponseEntity<Referral> referralsQualify(UUID id, String idempotencyKey, ReferralsQualifyRequest body) {
        String reason = Json.text(http.tree(body), "reason");
        return http.ok(idempotencyKey, "referral.qualify", id, body, Referral.class, () -> referrals.qualify(id, reason));
    }

    @Override
    public ResponseEntity<Referral> referralsPay(UUID id, String idempotencyKey) {
        return http.ok(idempotencyKey, "referral.pay", id, null, Referral.class, () -> referrals.pay(id));
    }

    @Override
    public ResponseEntity<Referral> referralsReject(UUID id, String idempotencyKey, ReferralsRejectRequest body) {
        String reason = Json.required(http.tree(body), "rejection_reason");
        return http.ok(idempotencyKey, "referral.reject", id, body, Referral.class, () -> referrals.reject(id, reason));
    }
}
