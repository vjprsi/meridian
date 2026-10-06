package io.meridian.statement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** statement-service: Generates account statements (PDF, CSV, camt.053, MT940, OFX) per cycle or on demand from ledger data, delivers them by email, in-app, post or SFTP, and produces interest and tax c */
@SpringBootApplication
public class StatementApplication {
    public static void main(String[] args) {
        SpringApplication.run(StatementApplication.class, args);
    }
}
