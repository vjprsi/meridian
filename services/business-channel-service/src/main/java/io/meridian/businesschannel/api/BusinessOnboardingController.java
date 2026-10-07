package io.meridian.businesschannel.api;

import io.meridian.businesschannel.api.generated.BusinessOnboardingApi;
import io.meridian.businesschannel.api.generated.model.BusinessOnboardingAddApplicationsDocumentsRequest;
import io.meridian.businesschannel.api.generated.model.BusinessOnboardingAddApplicationsRequest;
import io.meridian.businesschannel.api.generated.model.BusinessOnboardingAddApplicationsStepsRequest;
import io.meridian.businesschannel.api.generated.model.BusinessOnboardingApplicationsDocumentsCompleteRequest;
import io.meridian.businesschannel.api.generated.model.CustomerRelationshipPage;
import io.meridian.businesschannel.api.generated.model.DocumentPage;
import io.meridian.businesschannel.api.generated.model.OnboardingApplication;
import io.meridian.businesschannel.api.generated.model.OnboardingApplicationPage;
import io.meridian.businesschannel.api.generated.model.OnboardingJourneyPage;
import io.meridian.businesschannel.api.generated.model.OnboardingStep;
import io.meridian.businesschannel.api.generated.model.OnboardingStepPage;
import io.meridian.businesschannel.application.KybService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** KYB of the business through the channel (ADMIN users; wraps onboarding-, document- and customer-service). */
@RestController
public class BusinessOnboardingController implements BusinessOnboardingApi {
    private final KybService kyb;
    private final Http http;

    public BusinessOnboardingController(KybService kyb, Http http) {
        this.kyb = kyb;
        this.http = http;
    }

    @Override
    public ResponseEntity<OnboardingJourneyPage> businessOnboardingJourneys(Integer limit, String cursor) {
        return http.ok(kyb.journeys(), OnboardingJourneyPage.class);
    }

    @Override
    public ResponseEntity<OnboardingApplicationPage> businessOnboardingApplications(Integer limit, String cursor) {
        return http.ok(kyb.applications(limit, cursor), OnboardingApplicationPage.class);
    }

    @Override
    public ResponseEntity<OnboardingApplication> businessOnboardingAddApplications(String idempotencyKey,
            BusinessOnboardingAddApplicationsRequest body) {
        return http.ok(kyb.start(http.body(body), idempotencyKey), OnboardingApplication.class);
    }

    @Override
    public ResponseEntity<OnboardingApplication> businessOnboardingGetApplicationsById(UUID applicationId) {
        return http.ok(kyb.application(applicationId), OnboardingApplication.class);
    }

    @Override
    public ResponseEntity<OnboardingStepPage> businessOnboardingApplicationsSteps(UUID applicationId, Integer limit,
                                                                                  String cursor) {
        return http.ok(kyb.steps(applicationId, limit, cursor), OnboardingStepPage.class);
    }

    @Override
    public ResponseEntity<OnboardingStep> businessOnboardingApplicationsNextStep(UUID applicationId) {
        return http.ok(kyb.nextStep(applicationId), OnboardingStep.class);
    }

    @Override
    public ResponseEntity<OnboardingStep> businessOnboardingAddApplicationsSteps(UUID applicationId, String stepCode,
            String idempotencyKey, BusinessOnboardingAddApplicationsStepsRequest body) {
        return http.ok(kyb.submitStep(applicationId, stepCode, http.body(body), idempotencyKey), OnboardingStep.class);
    }

    @Override
    public ResponseEntity<DocumentPage> businessOnboardingApplicationsDocuments(UUID applicationId, Integer limit,
                                                                                String cursor) {
        return http.ok(kyb.documents(applicationId, limit, cursor), DocumentPage.class);
    }

    @Override
    public ResponseEntity<Map<String, Object>> businessOnboardingAddApplicationsDocuments(UUID applicationId,
            String idempotencyKey, BusinessOnboardingAddApplicationsDocumentsRequest body) {
        return http.object(kyb.addDocument(applicationId, http.body(body), idempotencyKey));
    }

    @Override
    public ResponseEntity<Map<String, Object>> businessOnboardingApplicationsDocumentsComplete(UUID applicationId,
            UUID documentId, String idempotencyKey, BusinessOnboardingApplicationsDocumentsCompleteRequest body) {
        return http.object(kyb.completeDocument(applicationId, documentId, http.body(body), idempotencyKey));
    }

    @Override
    public ResponseEntity<OnboardingApplication> businessOnboardingApplicationsSubmit(UUID applicationId,
                                                                                      String idempotencyKey) {
        return http.ok(kyb.submit(applicationId, idempotencyKey), OnboardingApplication.class);
    }

    @Override
    public ResponseEntity<CustomerRelationshipPage> businessOnboardingRelatedParties(Integer limit, String cursor) {
        return http.ok(kyb.relatedParties(limit, cursor), CustomerRelationshipPage.class);
    }
}
