package io.meridian.aiplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** ai-platform-service: Model registry with model risk management (validation, bias tests, explainability, approval), deployments in shadow/canary/champion-challenger with monitoring, feature catalogue, c */
@SpringBootApplication
public class AiPlatformApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiPlatformApplication.class, args);
    }
}
