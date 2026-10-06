package io.meridian.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** payment-service: Payment orchestrator. Owns the payment lifecycle state machine (initiate, screen, approve, hold funds, route, submit, settle, return), payees and aliases, bulk payments, mandates,  */
@SpringBootApplication
public class PaymentApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }
}
