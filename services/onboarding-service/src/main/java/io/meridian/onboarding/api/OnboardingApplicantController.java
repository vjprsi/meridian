package io.meridian.onboarding.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.onboarding.api.generated.OnboardingApplicantApi;
import io.meridian.onboarding.api.generated.model.OnboardingApplicantDocumentsCompleteRequest;
import io.meridian.onboarding.api.generated.model.OnboardingApplicantDocumentsRequest;
import io.meridian.onboarding.api.generated.model.OnboardingApplicantOtpSendRequest;
import io.meridian.onboarding.api.generated.model.OnboardingApplicantOtpVerifyRequest;
import io.meridian.onboarding.api.generated.model.OnboardingApplicantStartRequest;
import io.meridian.onboarding.api.generated.model.OnboardingApplicantStepsRequest;
import io.meridian.onboarding.api.generated.model.OnboardingApplicantToken;
import io.meridian.onboarding.api.generated.model.OnboardingApplication;
import io.meridian.onboarding.api.generated.model.OnboardingOtp;
import io.meridian.onboarding.api.generated.model.OnboardingStep;
import io.meridian.onboarding.application.ApplicantService;
import io.meridian.onboarding.application.ApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.node.ObjectNode;

/**
 * Public applicant surface (no bearer token). The tenant comes from {@code ?tenant_id}; every action but start needs
 * the applicant token in {@value #TOKEN_HEADER}, checked before anything else so an unauthenticated caller learns
 * nothing about the application. The principal recorded on rows is {@code applicant:<application id>}.
 */
@RestController
public class OnboardingApplicantController implements OnboardingApplicantApi {
    public static final String TOKEN_HEADER = "X-Applicant-Token";

    private final ApplicantService applicant;
    private final ApplicationService apps;
    private final ActionRunner actions;
    private final String clientIpHeader;

    public OnboardingApplicantController(ApplicantService applicant, ApplicationService apps, ActionRunner actions,
                                         @Value("${meridian.onboarding.public.client-ip-header:}") String clientIpHeader) {
        this.applicant = applicant;
        this.apps = apps;
        this.actions = actions;
        this.clientIpHeader = clientIpHeader;
    }

    @Override
    public ResponseEntity<OnboardingApplicantToken> onboardingApplicantStart(UUID tenantId, OnboardingApplicantStartRequest body) {
        ObjectNode req = actions.tree(body);
        String ip = clientIp();
        ObjectNode token = as(tenantId, "applicant", () -> applicant.start(req, ip));
        return ResponseEntity.status(201).body(actions.model(token, OnboardingApplicantToken.class));
    }

    @Override
    public ResponseEntity<OnboardingApplication> onboardingApplicantGetGet(UUID id, UUID tenantId) {
        return ResponseEntity.ok(actions.model(authenticated(tenantId, id, () -> applicant.view(id)),
                OnboardingApplication.class));
    }

    @Override
    public ResponseEntity<OnboardingStep> onboardingApplicantNextStep(UUID id, UUID tenantId) {
        return ResponseEntity.ok(actions.model(authenticated(tenantId, id, () -> {
            expire(id);
            return apps.nextStep(id);
        }), OnboardingStep.class));
    }

    @Override
    public ResponseEntity<OnboardingStep> onboardingApplicantSteps(UUID id, String stepCode, UUID tenantId,
                                                                  OnboardingApplicantStepsRequest body) {
        ObjectNode req = actions.tree(body);
        return ResponseEntity.ok(actions.model(authenticated(tenantId, id, () -> {
            expire(id);
            return applicant.submitStep(id, stepCode, req.get("input"));
        }), OnboardingStep.class));
    }

    @Override
    public ResponseEntity<OnboardingOtp> onboardingApplicantOtpSend(UUID id, UUID tenantId, OnboardingApplicantOtpSendRequest body) {
        ObjectNode req = actions.tree(body);
        return ResponseEntity.ok(actions.model(authenticated(tenantId, id, () -> {
            expire(id);
            return applicant.sendOtp(id, req.path("step_code").asString(""), req.path("destination").asString(null));
        }), OnboardingOtp.class));
    }

    @Override
    public ResponseEntity<OnboardingStep> onboardingApplicantOtpVerify(UUID id, UUID tenantId,
                                                                      OnboardingApplicantOtpVerifyRequest body) {
        ObjectNode req = actions.tree(body);
        return ResponseEntity.ok(actions.model(authenticated(tenantId, id, () -> {
            expire(id);
            ApplicantService.OtpOutcome r = applicant.verifyOtp(id, req.path("step_code").asString(""),
                    req.path("code").asString(""));
            if (r.error() != null) {
                throw ApiException.unprocessable(r.error(), r.detail()); // after the attempt was committed
            }
            return r.step();
        }), OnboardingStep.class));
    }

    @Override
    public ResponseEntity<Map<String, Object>> onboardingApplicantDocuments(UUID id, UUID tenantId,
                                                                           OnboardingApplicantDocumentsRequest body) {
        ObjectNode req = actions.tree(body);
        return ResponseEntity.ok(document(authenticated(tenantId, id, () -> {
            expire(id);
            return applicant.createDocument(id, req);
        })));
    }

    @Override
    public ResponseEntity<Map<String, Object>> onboardingApplicantDocumentsComplete(
            UUID id, UUID documentId, UUID tenantId, OnboardingApplicantDocumentsCompleteRequest body) {
        ObjectNode req = actions.tree(body);
        return ResponseEntity.ok(document(authenticated(tenantId, id, () -> {
            expire(id);
            return applicant.completeDocument(id, documentId, req);
        })));
    }

    @Override
    public ResponseEntity<OnboardingApplication> onboardingApplicantSubmit(UUID id, UUID tenantId) {
        return ResponseEntity.ok(actions.model(authenticated(tenantId, id, () -> {
            expire(id);
            return applicant.submit(id);
        }), OnboardingApplication.class));
    }

    @Override
    public ResponseEntity<OnboardingApplicantToken> onboardingApplicantTokenRefresh(UUID id, UUID tenantId) {
        String token = header(TOKEN_HEADER);
        return ResponseEntity.ok(actions.model(as(tenantId, "applicant:" + id, () -> applicant.refresh(id, token)),
                OnboardingApplicantToken.class));
    }

    // ------------------------------------------------------------------ helpers

    /** Binds the tenant, checks the applicant token for this application, then runs the action. */
    private ObjectNode authenticated(UUID tenantId, UUID id, Callable<ObjectNode> action) {
        String token = header(TOKEN_HEADER);
        return as(tenantId, "applicant:" + id, () -> {
            applicant.authenticate(id, token);
            return action.call();
        });
    }

    private static ObjectNode as(UUID tenantId, String principal, Callable<ObjectNode> action) {
        try {
            return TenantContext.callAs(tenantId, principal, action);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void expire(UUID id) {
        if (apps.expireIfDue(id)) {
            throw ApiException.conflict("application_expired", "The application has expired; start a new one");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> document(ObjectNode node) {
        return actions.model(node, Map.class);
    }

    /** The client address: the configured proxy header's first hop (set by the gateway), else the socket peer. */
    private String clientIp() {
        HttpServletRequest req = request();
        if (req == null) {
            return null;
        }
        if (clientIpHeader != null && !clientIpHeader.isBlank()) {
            String v = req.getHeader(clientIpHeader);
            if (v != null && !v.isBlank()) {
                return v.split(",")[0].trim();
            }
        }
        return req.getRemoteAddr();
    }

    private static String header(String name) {
        HttpServletRequest req = request();
        return req == null ? null : req.getHeader(name);
    }

    private static HttpServletRequest request() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a ? a.getRequest() : null;
    }
}
