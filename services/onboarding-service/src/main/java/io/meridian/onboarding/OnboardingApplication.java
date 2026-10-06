package io.meridian.onboarding;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** onboarding-service: No-code onboarding journeys per product, segment and country; runs applications step by step across app, web, USSD, agent and branch channels, orchestrates KYC checks and decisions */
@SpringBootApplication
public class OnboardingApplication {
    public static void main(String[] args) {
        SpringApplication.run(OnboardingApplication.class, args);
    }
}
