package io.meridian.billing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** billing-service: SaaS commercial engine: price plans, tenant subscriptions, usage metering from platform events, and monthly invoicing with usage tiers. */
@SpringBootApplication
public class BillingApplication {
    public static void main(String[] args) {
        SpringApplication.run(BillingApplication.class, args);
    }
}
