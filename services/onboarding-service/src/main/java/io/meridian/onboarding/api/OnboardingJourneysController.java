package io.meridian.onboarding.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.onboarding.api.generated.OnboardingJourneysApi;
import io.meridian.onboarding.api.generated.model.OnboardingJourney;
import io.meridian.onboarding.api.generated.model.OnboardingJourneyCreate;
import io.meridian.onboarding.api.generated.model.OnboardingJourneyUpdate;
import io.meridian.onboarding.api.generated.model.OnboardingJourneysRetireRequest;
import io.meridian.onboarding.api.generated.model.OnboardingJourneysSimulateRequest;
import io.meridian.onboarding.application.JourneyService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

/** Onboarding journeys: versioned step graphs with maker-checker publication. */
@RestController
public class OnboardingJourneysController implements OnboardingJourneysApi {
    private final JourneyService journeys;
    private final ActionRunner actions;

    public OnboardingJourneysController(JourneyService journeys, ActionRunner actions) {
        this.journeys = journeys;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<OnboardingJourney> onboardingJourneysCreate(String idempotencyKey, OnboardingJourneyCreate body) {
        TenantContext.require();
        return actions.run(idempotencyKey, "create", null, body, OnboardingJourney.class, HttpStatus.CREATED,
                () -> journeys.create(body));
    }

    @Override
    public ResponseEntity<OnboardingJourney> onboardingJourneysUpdate(UUID id, String ifMatch, OnboardingJourneyUpdate body) {
        TenantContext.require();
        return actions.withEtag(journeys.update(id, body, ifMatch), OnboardingJourney.class);
    }

    @Override
    public ResponseEntity<OnboardingJourney> onboardingJourneysPublish(UUID id, String idempotencyKey) {
        TenantContext.require();
        return actions.run(idempotencyKey, "publish", id, null, OnboardingJourney.class, HttpStatus.OK,
                () -> journeys.publish(id));
    }

    @Override
    public ResponseEntity<OnboardingJourney> onboardingJourneysRetire(UUID id, String idempotencyKey,
                                                                    OnboardingJourneysRetireRequest body) {
        TenantContext.require();
        String reason = body == null ? null : body.getReason();
        return actions.run(idempotencyKey, "retire", id, body, OnboardingJourney.class, HttpStatus.OK,
                () -> journeys.retire(id, reason));
    }

    @Override
    public ResponseEntity<OnboardingJourney> onboardingJourneysNewVersion(UUID id, String idempotencyKey) {
        TenantContext.require();
        return actions.run(idempotencyKey, "new-version", id, null, OnboardingJourney.class, HttpStatus.CREATED,
                () -> journeys.newVersion(id));
    }

    /** Dry run: path, missing fields, risk and decision for the sample data. */
    @Override
    public ResponseEntity<Map<String, Object>> onboardingJourneysSimulate(UUID id, String idempotencyKey,
                                                                          OnboardingJourneysSimulateRequest body) {
        TenantContext.require();
        ObjectNode req = actions.tree(body);
        return actions.document(idempotencyKey, "simulate", id, body, HttpStatus.OK,
                () -> journeys.simulate(id, req.get("sample_data")));
    }
}
