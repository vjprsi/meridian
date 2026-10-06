package io.meridian.workflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** workflow-service: Maker-checker approvals for configuration and sensitive operations (multi-level, amount thresholds, segregation of duties), workflow definitions and instances on Temporal, and huma */
@SpringBootApplication
public class WorkflowApplication {
    public static void main(String[] args) {
        SpringApplication.run(WorkflowApplication.class, args);
    }
}
