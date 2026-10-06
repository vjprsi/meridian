package io.meridian.statement.config;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StatementConfig.StatementProperties.class)
@EnableScheduling
public class StatementConfig {

    /**
     * {@code meridian.statement.*}.
     *
     * @param downloadSecret HMAC key for signed download links
     * @param linkTtlSeconds lifetime of a signed download link
     * @param batchEnabled   run the daily statement cycle
     * @param batchTenants   tenants processed by the daily cycle
     */
    @ConfigurationProperties(prefix = "meridian.statement")
    public record StatementProperties(String downloadSecret, Long linkTtlSeconds, boolean batchEnabled,
                                      List<UUID> batchTenants) {
        public StatementProperties {
            downloadSecret = downloadSecret == null || downloadSecret.isBlank() ? "change-me-statement-links" : downloadSecret;
            linkTtlSeconds = linkTtlSeconds == null ? 900L : linkTtlSeconds;
            batchTenants = batchTenants == null ? List.of() : List.copyOf(batchTenants);
        }
    }

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }
}
