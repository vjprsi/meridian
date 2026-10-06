package io.meridian.cardtokenization;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** card-tokenization-service: Network tokenisation through scheme token services (VTS, MDES) for Apple Pay, Google Pay, Samsung Pay and merchant card-on-file, including provisioning decisions, step-up (OTP / ap */
@SpringBootApplication
public class CardTokenizationApplication {
    public static void main(String[] args) {
        SpringApplication.run(CardTokenizationApplication.class, args);
    }
}
