package io.meridian.collections.application;

import io.meridian.collections.domain.ArrangementTracker.Payment;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** What collections needs from the servicing systems (loan-service for loans, BNPL and credit lines). */
public interface ExposurePort {

    /** A delinquent (or re-checked) exposure as reported by its servicing system. */
    record Exposure(UUID id, String exposureType, UUID customerId, UUID productId, String riskGrade, String status,
                    int daysPastDue, BigDecimal overdueAmount, BigDecimal totalOutstanding, String currency) {

        /** The exposure no longer owes anything. */
        public boolean paidOff() {
            return "PAID_OFF".equals(status) || "CLOSED".equals(status);
        }

        public boolean writtenOff() {
            return "WRITTEN_OFF".equals(status) || "CHARGED_OFF".equals(status);
        }
    }

    /** Every exposure the servicing systems report IN_ARREARS (all pages). */
    List<Exposure> delinquent();

    /** The current state of one exposure; empty for exposure types this port does not serve (cards, overdrafts). */
    Optional<Exposure> get(String exposureType, UUID id);

    /** Repayments received on the exposure (all statuses; reversals included). */
    List<Payment> payments(String exposureType, UUID id);

    static boolean servedByLoanService(String exposureType) {
        return "LOAN".equals(exposureType) || "BNPL".equals(exposureType) || "CREDIT_LINE".equals(exposureType);
    }
}
