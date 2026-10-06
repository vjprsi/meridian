package io.meridian.document.application;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.document.infrastructure.DocumentStore;
import java.time.LocalDate;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic sweeps per tenant: identity-document expiry alerts and e-sign envelope expiry. Tenants are discovered
 * from recent API activity because row-level security hides other tenants' rows from the application role.
 */
@Component
@ConditionalOnProperty(name = "meridian.documents.jobs-enabled", havingValue = "true", matchIfMissing = true)
public class DocumentJobs {
    private static final Logger log = LoggerFactory.getLogger(DocumentJobs.class);

    private final DocumentStore store;
    private final DocumentService documents;
    private final EsignService esign;
    private final int expiryWarningDays;

    public DocumentJobs(DocumentStore store, DocumentService documents, EsignService esign,
                        @Value("${meridian.documents.expiry-warning-days:30}") int expiryWarningDays) {
        this.store = store;
        this.documents = documents;
        this.esign = esign;
        this.expiryWarningDays = expiryWarningDays;
    }

    @Scheduled(fixedDelayString = "${meridian.documents.jobs-interval-ms:300000}", initialDelay = 60_000)
    public void run() {
        for (UUID tenant : store.activeTenants()) {
            try {
                TenantContext.callAs(tenant, "system", () -> {
                    documents.notifyExpiring(LocalDate.now(), expiryWarningDays);
                    esign.expireOverdue();
                    return null;
                });
            } catch (Exception e) {
                log.warn("Document sweep failed for tenant {}: {}", tenant, e.toString());
            }
        }
    }
}
