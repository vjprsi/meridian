package io.meridian.commission;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** commission-service: Multi-level commission engine: versioned plans with rules by transaction type, channel, tier, agent level, territory and biller; real-time accrual on every completed transaction; p */
@SpringBootApplication
public class CommissionApplication {
    public static void main(String[] args) {
        SpringApplication.run(CommissionApplication.class, args);
    }
}
