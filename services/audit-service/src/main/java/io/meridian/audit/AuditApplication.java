package io.meridian.audit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** audit-service: Tamper-evident, hash-chained audit log of every user, API and system action, retained per jurisdiction (10 years minimum), with regulator / DSAR exports under maker-checker. */
@SpringBootApplication
public class AuditApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuditApplication.class, args);
    }
}
