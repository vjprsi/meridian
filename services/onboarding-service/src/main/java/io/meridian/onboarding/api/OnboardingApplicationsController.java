package io.meridian.onboarding.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.onboarding.api.generated.OnboardingApplicationsApi;
import io.meridian.onboarding.api.generated.model.OnboardingApplication;
import io.meridian.onboarding.api.generated.model.OnboardingApplicationCreate;
import io.meridian.onboarding.api.generated.model.OnboardingApplicationUpdate;
import io.meridian.onboarding.api.generated.model.OnboardingApplicationsApproveRequest;
import io.meridian.onboarding.api.generated.model.OnboardingApplicationsRejectRequest;
import io.meridian.onboarding.api.generated.model.OnboardingApplicationsRequestInfoRequest;
import io.meridian.onboarding.api.generated.model.OnboardingApplicationsStepsRequest;
import io.meridian.onboarding.api.generated.model.OnboardingStep;
import io.meridian.onboarding.api.generated.model.OnboardingStepPage;
import io.meridian.onboarding.application.ApplicationService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

/** Onboarding applications: step engine, submission and decision, maker-checker review, abandon and resume. */
@RestController
public class OnboardingApplicationsController implements OnboardingApplicationsApi {
    private final ApplicationService apps;
    private final ActionRunner actions;
    private final CrudEngine crud;

    public OnboardingApplicationsController(ApplicationService apps, ActionRunner actions, CrudEngine crud) {
        this.apps = apps;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<OnboardingApplication> onboardingApplicationsCreate(String idempotencyKey,
                                                                            OnboardingApplicationCreate body) {
        TenantContext.require();
        return actions.run(idempotencyKey, "create", null, body, OnboardingApplication.class, HttpStatus.CREATED,
                () -> apps.create(body));
    }

    @Override
    public ResponseEntity<OnboardingApplication> onboardingApplicationsUpdate(UUID id, String ifMatch,
                                                                            OnboardingApplicationUpdate body) {
        TenantContext.require();
        expire(id);
        return actions.withEtag(apps.update(id, body, ifMatch), OnboardingApplication.class);
    }

    @Override
    public ResponseEntity<OnboardingStep> onboardingApplicationsNextStep(UUID id) {
        TenantContext.require();
        expire(id);
        return ResponseEntity.ok(actions.model(apps.nextStep(id), OnboardingStep.class));
    }

    @Override
    public ResponseEntity<OnboardingStepPage> onboardingApplicationsGetSteps(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        crud.findOne("onboarding_application", java.util.Map.of("id", id))
                .orElseThrow(() -> ApiException.notFound("onboarding application", id));
        return crud.list("onboarding_step", OnboardingStepPage.class,
                Crud.params().with("onboarding_application_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<OnboardingStep> onboardingApplicationsSteps(UUID id, String stepCode, String idempotencyKey,
                                                                    OnboardingApplicationsStepsRequest body) {
        TenantContext.require();
        ObjectNode req = actions.tree(body);
        return actions.run(idempotencyKey, "step:" + stepCode, id, body, OnboardingStep.class, HttpStatus.OK, () -> {
            expire(id);
            return apps.submitStep(id, stepCode, req.get("input"));
        });
    }

    @Override
    public ResponseEntity<OnboardingApplication> onboardingApplicationsSubmit(UUID id, String idempotencyKey) {
        TenantContext.require();
        return actions.run(idempotencyKey, "submit", id, null, OnboardingApplication.class, HttpStatus.OK, () -> {
            expire(id);
            return apps.submit(id);
        });
    }

    @Override
    public ResponseEntity<OnboardingApplication> onboardingApplicationsApprove(UUID id, String idempotencyKey,
                                                                             OnboardingApplicationsApproveRequest body) {
        TenantContext.require();
        ObjectNode req = actions.tree(body);
        return actions.run(idempotencyKey, "approve", id, body, OnboardingApplication.class, HttpStatus.OK,
                () -> apps.approve(id, req.path("comment").asString(null)));
    }

    @Override
    public ResponseEntity<OnboardingApplication> onboardingApplicationsReject(UUID id, String idempotencyKey,
                                                                            OnboardingApplicationsRejectRequest body) {
        TenantContext.require();
        ObjectNode req = actions.tree(body);
        return actions.run(idempotencyKey, "reject", id, body, OnboardingApplication.class, HttpStatus.OK,
                () -> apps.reject(id, req.path("reason").asString()));
    }

    @Override
    public ResponseEntity<OnboardingApplication> onboardingApplicationsRequestInfo(
            UUID id, String idempotencyKey, OnboardingApplicationsRequestInfoRequest body) {
        TenantContext.require();
        ObjectNode req = actions.tree(body);
        return actions.run(idempotencyKey, "request-info", id, body, OnboardingApplication.class, HttpStatus.OK,
                () -> apps.requestInfo(id, req.path("message").asString(), req.get("step_codes")));
    }

    @Override
    public ResponseEntity<OnboardingApplication> onboardingApplicationsAbandon(UUID id, String idempotencyKey) {
        TenantContext.require();
        return actions.run(idempotencyKey, "abandon", id, null, OnboardingApplication.class, HttpStatus.OK, () -> {
            expire(id);
            return apps.abandon(id);
        });
    }

    /** Contract declares no body (defect); 202 with the new expiry and the delivery channel. */
    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public ResponseEntity onboardingApplicationsResume(UUID id, String idempotencyKey) {
        TenantContext.require();
        return actions.run(idempotencyKey, "resume", id, null, ObjectNode.class, HttpStatus.ACCEPTED, () -> {
            expire(id);
            return apps.resume(id, idempotencyKey);
        });
    }

    /** Lazily expires an idle application in its own transaction, then rejects the request. */
    private void expire(UUID id) {
        if (apps.expireIfDue(id)) {
            throw ApiException.conflict("application_expired", "The application has expired; start a new one");
        }
    }
}
