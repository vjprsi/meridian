package io.meridian.ledger.config;

import io.meridian.common.refs.CounterpartyAccounts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Counterparty account pseudonyms of payment references. The secret {@code meridian.refs.counterparty-key} (env
 * {@code MERIDIAN_REFS_COUNTERPARTY_KEY}, Helm secret {@code meridian-ledger-service-secrets/counterparty-key}) must
 * be the same in ledger-service and reconciliation-service; it is a required secret outside security-mode=dev.
 */
@Configuration
public class RefsConfig {
    @Bean
    CounterpartyAccounts counterpartyAccounts(
            @Value("${meridian.refs.counterparty-key:" + CounterpartyAccounts.DEV_KEY + "}") String key) {
        return new CounterpartyAccounts(key);
    }
}
