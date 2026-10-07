package io.meridian.audit.config;

import io.meridian.audit.domain.PiiMasker;
import io.meridian.audit.infrastructure.S3WormStore;
import io.meridian.audit.infrastructure.WormStore;
import io.meridian.web.lifecycle.PersonalDataCatalog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** Audit integrity wiring: PII masking of stored states (GAP-07) and the WORM store for sealed daily batches. */
@Configuration(proxyBeanMethods = false)
public class AuditConfig {
    /** Development default, accepted only in security-mode=dev (RequiredSecrets refuses it elsewhere). */
    static final String DEV_KEY = "dev-only-audit-pseudonym-key-not-for-production";

    /** Masks the spec's pii columns (all services' personal-data maps) and personal field names. */
    @Bean
    PiiMasker piiMasker(JsonMapper json, @Value("${meridian.audit.pseudonym-key:" + DEV_KEY + "}") String key) {
        return new PiiMasker(PersonalDataCatalog.piiColumnsByTable(json), key);
    }

    /** S3 API with Object Lock (AWS S3 in production, SeaweedFS / MinIO locally). */
    @Bean
    @ConditionalOnMissingBean(WormStore.class)
    WormStore auditWormStore(@Value("${meridian.audit.worm.endpoint:http://localhost:19000}") String endpoint,
                             @Value("${meridian.audit.worm.bucket:meridian-audit}") String bucket,
                             @Value("${meridian.audit.worm.region:us-east-1}") String region,
                             @Value("${meridian.audit.worm.access-key:meridian}") String accessKey,
                             @Value("${meridian.audit.worm.secret-key:meridian-secret}") String secretKey,
                             @Value("${meridian.audit.worm.object-lock-mode:COMPLIANCE}") String lockMode) {
        return new S3WormStore(endpoint, bucket, region, accessKey, secretKey, lockMode);
    }
}
