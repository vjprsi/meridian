package io.meridian.reconciliation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** reconciliation-service: Imports external statements and settlement files, matches them against internal ledger records with configurable rules (and AI suggestions), and manages breaks in ageing exception  */
@SpringBootApplication
public class ReconciliationApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReconciliationApplication.class, args);
    }
}
