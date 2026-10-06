package io.meridian.kyc.application;

import static io.meridian.kyc.application.KycSupport.text;
import static io.meridian.kyc.application.KycSupport.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.kyc.api.generated.model.KycRiskAssessment;
import io.meridian.kyc.domain.KycRules;
import io.meridian.kyc.infrastructure.Rows;
import io.meridian.web.crud.CrudEngine;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

/** Manual (analyst) risk assessments; append-only, and they move the profile's current risk level. */
@Service
public class RiskAssessmentService {
    static final String TABLE = "kyc_risk_assessment";

    private final Rows rows;
    private final CrudEngine crud;
    private final KycSupport kyc;
    private final ProfileService profiles;

    public RiskAssessmentService(Rows rows, CrudEngine crud, KycSupport kyc, ProfileService profiles) {
        this.rows = rows;
        this.crud = crud;
        this.kyc = kyc;
        this.profiles = profiles;
    }

    @Transactional
    public ObjectNode create(Object body) {
        ObjectNode v = crud.toColumns(TABLE, body);
        int score = v.get("score").asInt();
        if (score < 0 || score > 1000) {
            throw ApiException.unprocessable("invalid_score", "score must be between 0 and 1000");
        }
        String level = text(v, "risk_level");
        String override = text(v, "override_level");
        if (override != null && (text(v, "override_reason") == null || text(v, "override_reason").isBlank())) {
            throw ApiException.unprocessable("override_reason_required", "An override level requires an override_reason");
        }
        if (override == null && v.hasNonNull("override_reason")) {
            throw ApiException.unprocessable("override_level_required", "override_reason given without override_level");
        }
        if (!KycRules.RISK_LEVELS.contains(level)) {
            throw ApiException.unprocessable("invalid_risk_level", "Unknown risk level");
        }
        if (!v.hasNonNull("assessed_by") && KycSupport.principalId() != null) {
            v.put("assessed_by", KycSupport.principalId().toString());
        }
        ObjectNode profile = profiles.provision(uuid(v, "kyc_profile_id"), uuid(v, "customer_id"));
        ObjectNode row = rows.insert(TABLE, v);
        String effective = override != null ? override : level;
        String previous = text(profile, "risk_level");
        ObjectNode p = rows.object();
        p.put("risk_level", effective);
        p.put("risk_score", score);
        if ("HIGH".equals(effective) && !KycSupport.bool(profile, "edd_required")) {
            p.put("edd_required", true);
            p.putNull("edd_completed_at");
        }
        UUID profileId = uuid(profile, "id");
        rows.update(ProfileService.TABLE, profileId, p);
        if (!Objects.equals(previous, effective)) {
            kyc.publish("party.kyc_profile.risk_changed", TABLE, row, KycRiskAssessment.class);
        }
        return row;
    }
}
