package io.meridian.webhook;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** webhook-service: Delivers domain events to client endpoints: signed (HMAC-SHA256 + timestamp), at-least-once with exponential retries for 72 hours, ordering per aggregate, delivery logs and event r */
@SpringBootApplication
public class WebhookApplication {
    public static void main(String[] args) {
        SpringApplication.run(WebhookApplication.class, args);
    }
}
