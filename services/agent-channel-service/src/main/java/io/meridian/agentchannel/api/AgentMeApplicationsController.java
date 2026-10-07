package io.meridian.agentchannel.api;

import io.meridian.agentchannel.api.generated.AgentMeApplicationsApi;
import io.meridian.agentchannel.api.generated.model.AgentMeApplicationsAddStepsRequest;
import io.meridian.agentchannel.api.generated.model.AgentMeApplicationsCreateRequest;
import io.meridian.agentchannel.api.generated.model.AgentMeApplicationsDocumentsCompleteUploadRequest;
import io.meridian.agentchannel.api.generated.model.AgentMeApplicationsDocumentsRequest;
import io.meridian.agentchannel.api.generated.model.Document;
import io.meridian.agentchannel.api.generated.model.OnboardingApplication;
import io.meridian.agentchannel.api.generated.model.OnboardingApplicationPage;
import io.meridian.agentchannel.api.generated.model.OnboardingStep;
import io.meridian.agentchannel.api.generated.model.OnboardingStepPage;
import io.meridian.agentchannel.application.OnboardingService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Assisted onboarding: applications the agent runs for prospects, their steps and documents. */
@RestController
public class AgentMeApplicationsController implements AgentMeApplicationsApi {
    private final OnboardingService onboarding;
    private final Http http;

    public AgentMeApplicationsController(OnboardingService onboarding, Http http) {
        this.onboarding = onboarding;
        this.http = http;
    }

    @Override
    public ResponseEntity<OnboardingApplicationPage> agentMeApplicationsList(String status, Integer limit, String cursor) {
        return http.ok(onboarding.applications(status, limit, cursor), OnboardingApplicationPage.class);
    }

    @Override
    public ResponseEntity<OnboardingApplication> agentMeApplicationsCreate(String idempotencyKey,
                                                                          AgentMeApplicationsCreateRequest body) {
        return http.ok(onboarding.start(http.body(body), idempotencyKey), OnboardingApplication.class);
    }

    @Override
    public ResponseEntity<OnboardingApplication> agentMeApplicationsGet(UUID applicationId) {
        return http.ok(onboarding.application(applicationId), OnboardingApplication.class);
    }

    @Override
    public ResponseEntity<OnboardingStep> agentMeApplicationsNextStep(UUID applicationId) {
        return http.ok(onboarding.nextStep(applicationId), OnboardingStep.class);
    }

    @Override
    public ResponseEntity<OnboardingStepPage> agentMeApplicationsSteps(UUID applicationId, Integer limit, String cursor) {
        return http.ok(onboarding.steps(applicationId, limit, cursor), OnboardingStepPage.class);
    }

    @Override
    public ResponseEntity<OnboardingStep> agentMeApplicationsAddSteps(UUID applicationId, String stepCode,
                                                                     String idempotencyKey,
                                                                     AgentMeApplicationsAddStepsRequest body) {
        return http.ok(onboarding.submitStep(applicationId, stepCode, http.body(body), idempotencyKey),
                OnboardingStep.class);
    }

    @Override
    public ResponseEntity<Document> agentMeApplicationsDocuments(UUID applicationId, String idempotencyKey,
                                                                AgentMeApplicationsDocumentsRequest body) {
        return http.ok(onboarding.registerDocument(applicationId, http.body(body), idempotencyKey), Document.class);
    }

    @Override
    public ResponseEntity<Map<String, Object>> agentMeApplicationsDocumentsUploadUrl(UUID applicationId,
                                                                                     UUID documentId,
                                                                                     String idempotencyKey) {
        return http.object(onboarding.uploadUrl(applicationId, documentId, idempotencyKey));
    }

    @Override
    public ResponseEntity<Document> agentMeApplicationsDocumentsCompleteUpload(
            UUID applicationId, UUID documentId, String idempotencyKey,
            AgentMeApplicationsDocumentsCompleteUploadRequest body) {
        return http.ok(onboarding.completeUpload(applicationId, documentId, http.body(body), idempotencyKey),
                Document.class);
    }

    @Override
    public ResponseEntity<OnboardingApplication> agentMeApplicationsSubmit(UUID applicationId, String idempotencyKey) {
        return http.ok(onboarding.submit(applicationId, idempotencyKey), OnboardingApplication.class);
    }
}
