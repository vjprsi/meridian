package io.meridian.kyc.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Port to an external KYC/KYB provider. Implementations live in infrastructure; the first adapter whose
 * {@link #supports(Provider)} returns true handles the provider.
 */
public interface ProviderAdapter {

    /** Provider configuration needed by adapters (credentials are resolved by the adapter, never passed around). */
    record Provider(UUID id, String code, String baseUrl, int timeoutMs) {}

    /** What to verify. {@code subjectName} is PII: adapters must never log it. */
    record CheckRequest(UUID checkId, String checkType, String subjectName, String documentType, String documentCountry) {}

    /**
     * Provider outcome. {@code status} is a KycCheckStatus; IN_PROGRESS means the result arrives later through the
     * provider callback.
     */
    record CheckResult(String status, BigDecimal score, List<String> reasonCodes, String providerReference,
                       String summary, BigDecimal cost, String costCurrency) {}

    record ConnectionResult(boolean ok, long latencyMs, String message) {}

    boolean supports(Provider provider);

    CheckResult runCheck(Provider provider, CheckRequest request);

    ConnectionResult testConnection(Provider provider);
}
