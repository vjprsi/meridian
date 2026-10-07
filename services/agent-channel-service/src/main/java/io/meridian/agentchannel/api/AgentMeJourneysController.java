package io.meridian.agentchannel.api;

import io.meridian.agentchannel.api.generated.AgentMeJourneysApi;
import io.meridian.agentchannel.api.generated.model.OnboardingJourneyPage;
import io.meridian.agentchannel.application.OnboardingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Onboarding journeys available on the agent channel. */
@RestController
public class AgentMeJourneysController implements AgentMeJourneysApi {
    private final OnboardingService onboarding;
    private final Http http;

    public AgentMeJourneysController(OnboardingService onboarding, Http http) {
        this.onboarding = onboarding;
        this.http = http;
    }

    @Override
    public ResponseEntity<OnboardingJourneyPage> agentMeJourneysList(Integer limit, String cursor) {
        return http.ok(onboarding.journeys(limit, cursor), OnboardingJourneyPage.class);
    }
}
