package io.meridian.fraud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** fraud-service: Scores every authorisation, payment, wallet transfer, login and profile change in under 50 ms with rules (CEL expressions) plus ML models, maintains device intelligence and block/a */
@SpringBootApplication
public class FraudApplication {
    public static void main(String[] args) {
        SpringApplication.run(FraudApplication.class, args);
    }
}
