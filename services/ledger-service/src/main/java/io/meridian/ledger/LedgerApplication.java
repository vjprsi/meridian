package io.meridian.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** ledger-service: Posting engine and balance service. The only component allowed to change balances. Enforces double-entry, idempotency, holds and available-balance rules; publishes every posting. */
@SpringBootApplication
public class LedgerApplication {
    public static void main(String[] args) {
        SpringApplication.run(LedgerApplication.class, args);
    }
}
