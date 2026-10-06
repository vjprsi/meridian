package io.meridian.cardthreeds;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** card-threeds-service: 3-D Secure 2.x access control server (ACS) integration: risk-based frictionless decisions, challenges via OTP, app push or biometrics, and decoupled authentication. */
@SpringBootApplication
public class CardThreedsApplication {
    public static void main(String[] args) {
        SpringApplication.run(CardThreedsApplication.class, args);
    }
}
