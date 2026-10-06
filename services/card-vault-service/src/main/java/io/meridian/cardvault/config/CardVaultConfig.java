package io.meridian.cardvault.config;

import io.meridian.cardvault.application.Hsm;
import io.meridian.cardvault.infrastructure.SoftwareHsm;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CardVaultConfig {

    /** Software HSM unless a real HSM adapter bean is provided. */
    @Bean
    @ConditionalOnMissingBean(Hsm.class)
    Hsm hsm(@Value("${meridian.cards.vault.master-key:}") String masterKey) {
        return new SoftwareHsm(masterKey);
    }
}
