package io.meridian.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** notification-service: Event-driven multi-channel notifications (push, SMS, email, WhatsApp, in-app, USSD, voice) with localised templates, provider failover, delivery tracking, preferences and quiet hou */
@SpringBootApplication
public class NotificationApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotificationApplication.class, args);
    }
}
