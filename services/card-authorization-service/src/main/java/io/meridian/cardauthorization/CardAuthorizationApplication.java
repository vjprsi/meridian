package io.meridian.cardauthorization;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** card-authorization-service: Real-time authorisation: receives ISO 8583 from the card gateway, applies card status, controls, limits, fraud score and 3DS outcome, places or increments the ledger hold (or calls */
@SpringBootApplication
public class CardAuthorizationApplication {
    public static void main(String[] args) {
        SpringApplication.run(CardAuthorizationApplication.class, args);
    }
}
