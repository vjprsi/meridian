package io.meridian.carddispute.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * Scheme and regulatory time limits. All dates are business dates of the database clock, passed in by the caller.
 * <ul>
 *   <li>Chargeback filing: 120 calendar days from the transaction date (from the dispute date when unknown).</li>
 *   <li>Retrieval response: 30 days. Representment window: VISA 30, MASTERCARD 45, others 30 days.</li>
 *   <li>Pre-arbitration response: 30 days. Arbitration filing: 10 days.</li>
 *   <li>Regulatory (Reg E style): provisional credit decision within 10 business days, resolution within 45 days.</li>
 * </ul>
 */
public final class DisputeDeadlines {
    private DisputeDeadlines() {}

    public static final int CHARGEBACK_FILING_DAYS = 120;
    public static final int RETRIEVAL_DAYS = 30;
    public static final int PRE_ARBITRATION_DAYS = 30;
    public static final int ARBITRATION_DAYS = 10;
    public static final int RESOLUTION_DAYS = 45;
    public static final int PROVISIONAL_CREDIT_BUSINESS_DAYS = 10;
    public static final int APPROACHING_DAYS = 3;

    public static LocalDate chargebackFiling(String scheme, LocalDate transactionDate, LocalDate disputeDate) {
        return (transactionDate != null ? transactionDate : disputeDate).plusDays(CHARGEBACK_FILING_DAYS);
    }

    public static LocalDate retrieval(LocalDate today) {
        return today.plusDays(RETRIEVAL_DAYS);
    }

    public static int representmentWindowDays(String scheme) {
        return "MASTERCARD".equals(scheme) ? 45 : 30;
    }

    public static LocalDate representment(String scheme, LocalDate submitted) {
        return submitted.plusDays(representmentWindowDays(scheme));
    }

    public static LocalDate preArbitration(LocalDate today) {
        return today.plusDays(PRE_ARBITRATION_DAYS);
    }

    public static LocalDate arbitration(LocalDate today) {
        return today.plusDays(ARBITRATION_DAYS);
    }

    public static LocalDate regulatory(LocalDate disputeDate) {
        return disputeDate.plusDays(RESOLUTION_DAYS);
    }

    /** Provisional credit decision date: {@code n} business days after {@code from} (weekends skipped). */
    public static LocalDate plusBusinessDays(LocalDate from, int n) {
        LocalDate d = from;
        int left = n;
        while (left > 0) {
            d = d.plusDays(1);
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                left--;
            }
        }
        return d;
    }

    public static LocalDate provisionalCreditDecision(LocalDate disputeDate) {
        return plusBusinessDays(disputeDate, PROVISIONAL_CREDIT_BUSINESS_DAYS);
    }

    /** True when the deadline is today or within the next {@value #APPROACHING_DAYS} days. */
    public static boolean approaching(LocalDate deadline, LocalDate asOf) {
        return deadline != null && !deadline.isBefore(asOf) && !deadline.isAfter(asOf.plusDays(APPROACHING_DAYS));
    }

    public static boolean passed(LocalDate deadline, LocalDate asOf) {
        return deadline != null && deadline.isBefore(asOf);
    }
}
