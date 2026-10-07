package io.meridian.reconciliation.infrastructure;

import io.meridian.common.refs.CounterpartyAccounts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Counterparty account pseudonyms: ledger entries carry only {@code counterparty_account_hash}, so the account stated
 * on a bank statement is pseudonymised with the same secret before matching. {@code meridian.refs.counterparty-key}
 * (env {@code MERIDIAN_REFS_COUNTERPARTY_KEY}) must equal ledger-service's; it is a required secret outside
 * security-mode=dev.
 */
@Configuration
public class RefsConfig {
    @Bean
    CounterpartyAccounts counterpartyAccounts(
            @Value("${meridian.refs.counterparty-key:" + CounterpartyAccounts.DEV_KEY + "}") String key) {
        return new CounterpartyAccounts(key);
    }
}
