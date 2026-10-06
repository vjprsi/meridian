package io.meridian.kyc.api;

import io.meridian.kyc.api.generated.KycProfilesApi;
import io.meridian.kyc.api.generated.model.KycCheckPage;
import io.meridian.kyc.api.generated.model.KycProfile;
import io.meridian.kyc.api.generated.model.KycProfileCreate;
import io.meridian.kyc.api.generated.model.KycProfilesCompleteEddRequest;
import io.meridian.kyc.api.generated.model.KycProfilesRequireEddRequest;
import io.meridian.kyc.api.generated.model.KycRiskAssessmentPage;
import io.meridian.kyc.application.KycSupport;
import io.meridian.kyc.application.ProfileService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** KYC profiles: evaluation and enhanced due diligence. */
@RestController
public class KycProfilesController implements KycProfilesApi {
    private final ProfileService profiles;
    private final Actions actions;
    private final CrudEngine crud;
    private final KycSupport kyc;

    public KycProfilesController(ProfileService profiles, Actions actions, CrudEngine crud, KycSupport kyc) {
        this.profiles = profiles;
        this.actions = actions;
        this.crud = crud;
        this.kyc = kyc;
    }

    @Override
    public ResponseEntity<KycProfile> kycProfilesCreate(String idempotencyKey, KycProfileCreate body) {
        return actions.created(idempotencyKey, "profile.create", body, KycProfile.class, () -> profiles.create(body));
    }

    @Override
    public ResponseEntity<KycProfile> kycProfilesEvaluate(UUID id, String idempotencyKey) {
        return actions.ok(idempotencyKey, "profile.evaluate", id, null, KycProfile.class, () -> profiles.evaluate(id));
    }

    @Override
    public ResponseEntity<KycProfile> kycProfilesRequireEdd(UUID id, String idempotencyKey, KycProfilesRequireEddRequest body) {
        String reason = KycSupport.text(kyc.tree(body), "reason");
        return actions.ok(idempotencyKey, "profile.require-edd", id, body, KycProfile.class,
                () -> profiles.requireEdd(id, reason));
    }

    @Override
    public ResponseEntity<KycProfile> kycProfilesCompleteEdd(UUID id, String idempotencyKey, KycProfilesCompleteEddRequest body) {
        String notes = KycSupport.text(kyc.tree(body), "notes");
        return actions.ok(idempotencyKey, "profile.complete-edd", id, body, KycProfile.class,
                () -> profiles.completeEdd(id, notes));
    }

    @Override
    public ResponseEntity<KycCheckPage> kycProfilesChecks(UUID id, Integer limit, String cursor) {
        crud.get("kyc_profile", KycProfile.class, Crud.params().with("id", id));
        return crud.list("kyc_check", KycCheckPage.class,
                Crud.params().with("kyc_profile_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<KycRiskAssessmentPage> kycProfilesRiskAssessments(UUID id, Integer limit, String cursor) {
        crud.get("kyc_profile", KycProfile.class, Crud.params().with("id", id));
        return crud.list("kyc_risk_assessment", KycRiskAssessmentPage.class,
                Crud.params().with("kyc_profile_id", id).with("limit", limit).with("cursor", cursor));
    }
}
