package io.meridian.loanorigination.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A credit bureau integration. Implementations are Spring beans discovered by {@link #provider()}; a synchronous
 * bureau returns RECEIVED / NO_HIT / FAILED, an asynchronous one returns REQUESTED with a reference and delivers
 * the report later through the public bureau callback.
 */
public interface BureauAdapter {

    /** Provider code as used in {@code bureau_report.provider}, e.g. SANDBOX. */
    String provider();

    Result pull(Request request);

    record Request(UUID reportId, UUID customerId, String pullType, String consentReference) {}

    /** Normalised bureau response (null fields when unknown). */
    record Result(String status, String reference, Integer score, String band, BigDecimal totalDebt,
                  BigDecimal monthlyDebtService, Integer openAccounts, Integer delinquentAccounts, Integer worstDpd24m,
                  Integer inquiries6m, List<Map<String, Object>> tradelines, String failureReason) {

        public static Result requested(String reference) {
            return new Result("REQUESTED", reference, null, null, null, null, null, null, null, null, List.of(), null);
        }
    }
}
