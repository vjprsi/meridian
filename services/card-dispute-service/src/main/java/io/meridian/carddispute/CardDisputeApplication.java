package io.meridian.carddispute;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** card-dispute-service: Cardholder disputes and scheme chargebacks (Visa Resolve Online, Mastercom): intake, provisional credit, evidence, deadlines per stage, representment handling and final outcome pos */
@SpringBootApplication
public class CardDisputeApplication {
    public static void main(String[] args) {
        SpringApplication.run(CardDisputeApplication.class, args);
    }
}
