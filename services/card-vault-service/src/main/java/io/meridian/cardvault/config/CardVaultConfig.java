package io.meridian.cardvault.config;

import io.meridian.cardvault.application.Hsm;
import io.meridian.cardvault.application.MasterKeyProvider;
import io.meridian.cardvault.infrastructure.AwsKmsKeyManagement;
import io.meridian.cardvault.infrastructure.EnvelopeHsm;
import io.meridian.cardvault.infrastructure.MasterKeyProviders;
import io.meridian.cardvault.infrastructure.Pkcs11Hsm;
import io.meridian.cardvault.infrastructure.SoftwareKeyManagement;
import io.meridian.web.security.RequiredSecrets;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.KmsClientBuilder;

/**
 * Card key management (GAP-01), chosen by {@code meridian.cards.vault.key-management}:
 *
 * <ul>
 *   <li>{@code software} (default; development and tests): envelope encryption under a software master key
 *       ({@code MERIDIAN_CARDS_VAULT_MASTER_KEY} / file, previous keys in {@code master-key-previous} during a
 *       rotation); outside dev mode the key must come from the secret store (SEC-01).</li>
 *   <li>{@code aws-kms}: envelope encryption under the CDE KMS key {@code meridian.cards.vault.kms.key-id} (IAM role
 *       of the pod, IRSA); a software master key, if still configured, only serves legacy keys until adopted.</li>
 *   <li>{@code pkcs11}: keys and PIN translation inside a PKCS#11 HSM (CloudHSM / Luna) through SunPKCS11.</li>
 * </ul>
 * A custom {@link Hsm} or {@link MasterKeyProvider} bean replaces the defaults.
 */
@Configuration(proxyBeanMethods = false)
public class CardVaultConfig {
    private static final Logger log = LoggerFactory.getLogger(CardVaultConfig.class);
    static final String P = "meridian.cards.vault.";

    @Bean
    @ConditionalOnMissingBean(Hsm.class)
    Hsm hsm(Environment env, ObjectProvider<MasterKeyProvider> customMaster, ObjectProvider<KmsClient> customKms) {
        String mode = env.getProperty(P + "key-management", "software").trim().toLowerCase(Locale.ROOT);
        boolean dev = RequiredSecrets.devMode(env);
        Duration ttl = env.getProperty(P + "data-key-cache-ttl", Duration.class, Duration.ofMinutes(5));
        int max = env.getProperty(P + "data-key-cache-max-entries", Integer.class, 10_000);
        Hsm hsm = switch (mode) {
            case "software" -> {
                MasterKeyProvider current = customMaster.getIfAvailable(() -> MasterKeyProviders.fromEnvironment(env));
                MasterKeyProviders.validate(current, dev);
                List<MasterKeyProvider> all = new ArrayList<>();
                all.add(current);
                all.addAll(MasterKeyProviders.previous(env, dev));
                byte[] legacy = current.masterKey();
                try {
                    yield new EnvelopeHsm(new SoftwareKeyManagement(all), legacy, ttl, max);
                } finally {
                    Arrays.fill(legacy, (byte) 0);
                }
            }
            case "aws-kms" -> {
                KmsClient kms = customKms.getIfAvailable(() -> kmsClient(env));
                MasterKeyProvider legacyKey = customMaster.getIfAvailable(() -> MasterKeyProviders.optional(env));
                byte[] legacy = null;
                if (legacyKey != null) {
                    MasterKeyProviders.validate(legacyKey, dev);
                    legacy = legacyKey.masterKey();
                }
                try {
                    yield new EnvelopeHsm(new AwsKmsKeyManagement(kms, env.getProperty(P + "kms.key-id", "")), legacy,
                            ttl, max);
                } finally {
                    if (legacy != null) {
                        Arrays.fill(legacy, (byte) 0);
                    }
                }
            }
            case "pkcs11" -> {
                String cfg = env.getProperty(P + "pkcs11.config", "").trim();
                char[] pin = env.getProperty(P + "pkcs11.pin", "").toCharArray();
                try {
                    yield Pkcs11Hsm.sunPkcs11(cfg.isEmpty() ? null : Path.of(cfg), pin,
                            env.getProperty(P + "pkcs11.key-alias-prefix", "meridian/"),
                            env.getProperty(P + "pkcs11.pin-block-key-algorithm", "GenericSecret"));
                } finally {
                    Arrays.fill(pin, '\0');
                }
            }
            default -> throw new IllegalStateException(P + "key-management must be software, aws-kms or pkcs11, got "
                    + mode);
        };
        if ("software".equals(mode) && !dev) {
            log.warn("Card vault keys are wrapped by a software master key: use key-management=aws-kms or pkcs11 for "
                    + "card data (PCI DSS 3.6/3.7, GAP-01)");
        }
        log.info("Card vault key management: {}", hsm.describe());
        return hsm;
    }

    static KmsClient kmsClient(Environment env) {
        KmsClientBuilder b = KmsClient.builder().httpClient(UrlConnectionHttpClient.create());
        String region = env.getProperty(P + "kms.region", "").trim();
        if (!region.isEmpty()) {
            b.region(Region.of(region));
        }
        String endpoint = env.getProperty(P + "kms.endpoint", "").trim();
        if (!endpoint.isEmpty()) {
            b.endpointOverride(URI.create(endpoint)); // VPC interface endpoint of KMS in the CDE
        }
        return b.build();
    }
}
