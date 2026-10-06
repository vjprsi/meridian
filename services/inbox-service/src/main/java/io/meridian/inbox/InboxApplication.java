package io.meridian.inbox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** inbox-service: Secure, authenticated two-way messaging between customers and the bank with attachments and read receipts, plus in-app announcements and banners. */
@SpringBootApplication
public class InboxApplication {
    public static void main(String[] args) {
        SpringApplication.run(InboxApplication.class, args);
    }
}
