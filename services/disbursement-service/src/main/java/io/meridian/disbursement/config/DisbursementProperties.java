package io.meridian.disbursement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * disbursement-service settings ({@code meridian.disbursement.*}).
 *
 * @param defaultCallingCode country calling code (digits) applied to national numbers ("07..") in beneficiary files;
 *                           empty = only international numbers are accepted
 * @param maxLines           maximum beneficiary lines per batch file
 */
@ConfigurationProperties(prefix = "meridian.disbursement")
public record DisbursementProperties(String defaultCallingCode, Integer maxLines) {
    public DisbursementProperties {
        maxLines = maxLines == null ? 50_000 : maxLines;
    }
}
