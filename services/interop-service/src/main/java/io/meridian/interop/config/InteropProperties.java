package io.meridian.interop.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * interop-service settings ({@code meridian.interop.*}). Switch callback secrets are read per switch code from
 * {@code meridian.interop.switch-secrets.<SWITCH_CODE>} (resolved from the vault in deployed environments).
 *
 * @param simulatorSecret secret the built-in switch simulator derives payee fulfilments from
 */
@ConfigurationProperties(prefix = "meridian.interop")
public record InteropProperties(String simulatorSecret) {
    public InteropProperties {
        simulatorSecret = simulatorSecret == null ? "meridian-switch-simulator" : simulatorSecret;
    }
}
