package io.meridian.kyc.api;

import io.meridian.kyc.api.generated.KycRiskAssessmentsApi;
import io.meridian.kyc.api.generated.model.KycRiskAssessment;
import io.meridian.kyc.api.generated.model.KycRiskAssessmentCreate;
import io.meridian.kyc.application.RiskAssessmentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Manual risk assessments (append-only). */
@RestController
public class KycRiskAssessmentsController implements KycRiskAssessmentsApi {
    private final RiskAssessmentService assessments;
    private final Actions actions;

    public KycRiskAssessmentsController(RiskAssessmentService assessments, Actions actions) {
        this.assessments = assessments;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<KycRiskAssessment> kycRiskAssessmentsCreate(String idempotencyKey, KycRiskAssessmentCreate body) {
        return actions.created(idempotencyKey, "risk-assessment.create", body, KycRiskAssessment.class,
                () -> assessments.create(body));
    }
}
