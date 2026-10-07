package io.meridian.cardvault.application;

import io.meridian.cardvault.infrastructure.VaultStore;
import io.meridian.common.tenancy.TenantContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Master key rotation job (GAP-01; runbook docs/operations/RUNBOOKS.md section 2.2): for every tenant holding vault
 * keys, re-wraps the data keys still wrapped under an older master key (KMS ReEncrypt; nothing else changes,
 * envelope encryption needs no bulk re-encryption of card data) and adopts keys from before envelope encryption.
 * Idempotent: keys already under the current master key are left alone, so the daily run costs one query per tenant
 * once a rotation has completed. Failures are logged per tenant and retried on the next run.
 */
@Component
public class MasterKeyRotationJob {
    private static final Logger log = LoggerFactory.getLogger(MasterKeyRotationJob.class);
    static final AccessLog.Caller JOB = new AccessLog.Caller("card-vault-service", "system:master-key-rotation", null);

    private final VaultStore store;
    private final VaultService vault;
    private final boolean enabled;

    public MasterKeyRotationJob(VaultStore store, VaultService vault,
                                @Value("${meridian.cards.vault.master-key-rotation.enabled:true}") boolean enabled) {
        this.store = store;
        this.vault = vault;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${meridian.cards.vault.master-key-rotation.cron:0 40 2 * * *}", zone = "UTC")
    public void scheduled() {
        if (enabled) {
            runAll();
        }
    }

    /** Re-wraps every tenant's keys; returns the per-tenant outcome (or the error message). */
    public Map<UUID, Object> runAll() {
        Map<UUID, Object> out = new LinkedHashMap<>();
        for (UUID tenant : store.keyTenants()) {
            try {
                Map<String, Object> r = TenantContext.callAs(tenant, JOB.actor(), () -> vault.rewrapMasterKey(JOB));
                out.put(tenant, r);
                if (((Number) r.get("rewrapped")).intValue() + ((Number) r.get("adopted")).intValue() > 0) {
                    log.info("Card vault keys of tenant {} re-wrapped under {}: {}", tenant, r.get("master_key_ref"), r);
                }
            } catch (Exception e) {
                out.put(tenant, e.toString());
                log.error("Card vault master key re-wrap failed for tenant {}: {}", tenant, e.toString());
            }
        }
        return out;
    }
}
