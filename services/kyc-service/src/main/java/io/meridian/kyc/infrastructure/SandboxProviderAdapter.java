package io.meridian.kyc.infrastructure;

import io.meridian.kyc.application.ProviderAdapter;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Deterministic provider for development and tests (code starting with SANDBOX or base_url "sandbox:").
 * The outcome is driven by markers in the subject name: REJECT fails, REVIEW is inconclusive (refer), ASYNC answers
 * later through the provider callback, ERROR simulates a provider fault; anything else passes.
 */
@Component
@Order(0)
public class SandboxProviderAdapter implements ProviderAdapter {
    private static final BigDecimal COST = new BigDecimal("0.10");

    @Override
    public boolean supports(Provider p) {
        return p.code().toUpperCase(Locale.ROOT).startsWith("SANDBOX")
                || (p.baseUrl() != null && p.baseUrl().startsWith("sandbox:"));
    }

    @Override
    public CheckResult runCheck(Provider p, CheckRequest r) {
        String name = r.subjectName() == null ? "" : r.subjectName().toUpperCase(Locale.ROOT);
        String ref = "sbx_" + UUID.randomUUID();
        if (name.contains("ERROR")) {
            return new CheckResult("ERROR", null, List.of("PROVIDER_UNAVAILABLE"), ref, "Sandbox provider error", null, null);
        }
        if (name.contains("ASYNC")) {
            return new CheckResult("IN_PROGRESS", null, List.of(), ref, "Awaiting provider result", COST, "USD");
        }
        if (name.contains("REJECT")) {
            return new CheckResult("FAILED", new BigDecimal("0.10"), List.of("DATA_MISMATCH"), ref,
                    "Verification failed", COST, "USD");
        }
        if (name.contains("REVIEW")) {
            return new CheckResult("INCONCLUSIVE", new BigDecimal("0.55"), List.of("MANUAL_REVIEW"), ref,
                    "Manual review required", COST, "USD");
        }
        return new CheckResult("PASSED", new BigDecimal("0.95"), List.of(), ref, "Verification passed", COST, "USD");
    }

    @Override
    public ConnectionResult testConnection(Provider p) {
        return new ConnectionResult(true, 0, "Sandbox provider reachable");
    }
}
