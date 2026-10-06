package io.meridian.insights;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** insights-service: Personal finance management: transaction enrichment (clean merchant names, logos, categories, geo, recurring flags), category taxonomy and customer overrides, budgets, subscription */
@SpringBootApplication
public class InsightsApplication {
    public static void main(String[] args) {
        SpringApplication.run(InsightsApplication.class, args);
    }
}
