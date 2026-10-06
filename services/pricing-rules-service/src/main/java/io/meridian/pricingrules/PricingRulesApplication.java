package io.meridian.pricingrules;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** pricing-rules-service: DMN-compatible decision engine used for pricing, eligibility, fees, limits and routing decisions. Decision tables are versioned with maker-checker; every evaluation is logged for e */
@SpringBootApplication
public class PricingRulesApplication {
    public static void main(String[] args) {
        SpringApplication.run(PricingRulesApplication.class, args);
    }
}
