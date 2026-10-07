package io.meridian.cardvault.config;

import io.meridian.cardvault.application.Hsm;
import io.meridian.cardvault.application.MasterKeyProvider;
import io.meridian.cardvault.infrastructure.MasterKeyProviders;
import io.meridian.cardvault.infrastructure.SoftwareHsm;
import io.meridian.web.security.RequiredSecrets;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class CardVaultConfig {
    private static final Logger log = LoggerFactory.getLogger(CardVaultConfig.class);

    /** Master key from the secret store / a mounted file; a KMS or HSM adapter bean replaces it (SEC-01). */
    @Bean
    @ConditionalOnMissingBean(MasterKeyProvider.class)
    MasterKeyProvider masterKeyProvider(Environment env) {
        return MasterKeyProviders.fromEnvironment(env);
    }

    /** Software HSM unless a real HSM adapter bean is provided. */
    @Bean
    @ConditionalOnMissingBean(Hsm.class)
    Hsm hsm(MasterKeyProvider keys, Environment env) {
        MasterKeyProviders.validate(keys, RequiredSecrets.devMode(env));
        byte[] key = keys.masterKey();
        try {
            log.info("Card vault software HSM master key source: {}", keys.source());
            return new SoftwareHsm(key);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }
}
