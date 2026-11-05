package io.meridian.casemanagement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * case-management-service settings ({@code meridian.casemanagement.*}).
 *
 * @param dispute WE-OPS-1 (D-ED-13, G-09) wallet dispute + correction settings
 */
@ConfigurationProperties(prefix = "meridian.casemanagement")
public record CaseManagementProperties(Dispute dispute) {

    public CaseManagementProperties {
        dispute = dispute == null ? new Dispute(null, null, null, null) : dispute;
    }

    /**
     * Wallet transaction-correction workflow + dispute tracker (WE-OPS-1). Entirely opt-in and per-tenant gated: with
     * {@code enabled} false (the default) every dispute / correction entry point is a {@code 422}, so existing
     * case-management behaviour is untouched (D-ED-14).
     *
     * @param enabled                turn the wallet dispute + correction workflow on for the tenant (default off)
     * @param slaHours               default dispute SLA window in hours (due_at = raised_at + slaHours)
     * @param repeatClaimantThreshold disputes by one claimant in the window above which the repeat-claimant flag trips
     * @param repeatClaimantWindowDays rolling window (days) over which a claimant's disputes are counted
     */
    public record Dispute(Boolean enabled, Integer slaHours, Integer repeatClaimantThreshold,
                          Integer repeatClaimantWindowDays) {
        public Dispute {
            enabled = enabled != null && enabled;
            slaHours = slaHours == null ? 120 : slaHours;                       // 5 days
            repeatClaimantThreshold = repeatClaimantThreshold == null ? 3 : repeatClaimantThreshold;
            repeatClaimantWindowDays = repeatClaimantWindowDays == null ? 30 : repeatClaimantWindowDays;
        }
    }
}
