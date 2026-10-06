package io.meridian.tenant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** tenant-service: Tenant lifecycle and placement on cells, legal entities, branches, per-tenant settings, feature flags, enabled modules and white-label branding. */
@SpringBootApplication
public class TenantApplication {
    public static void main(String[] args) {
        SpringApplication.run(TenantApplication.class, args);
    }
}
