package io.meridian.reconciliation.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * One parsed external line (statement or settlement record) before it is stored, normalised across formats
 * (camt.053/054, MT940/MT942, CSV, JSON). {@code externalRef} is the bank / servicer reference, {@code narrative}
 * the remittance information; further references and amounts are in {@link Details}.
 */
public record StatementLine(String externalRef, String endToEndId, LocalDate valueDate, LocalDate bookingDate,
                            Direction direction, BigDecimal amount, String currency, String counterparty,
                            String narrative, Map<String, String> raw, Details details) {

    public enum Direction { DEBIT, CREDIT }

    public StatementLine(String externalRef, String endToEndId, LocalDate valueDate, LocalDate bookingDate,
                         Direction direction, BigDecimal amount, String currency, String counterparty,
                         String narrative, Map<String, String> raw) {
        this(externalRef, endToEndId, valueDate, bookingDate, direction, amount, currency, counterparty, narrative,
                raw, Details.NONE);
    }

    public StatementLine {
        details = details == null ? Details.NONE : details;
    }

    /**
     * Additional references and amounts: ISO 20022 instruction id and UETR, counterparty account, bank transaction
     * code, reversal flag, charges deducted by the bank, the instructed (original) amount before charges / FX,
     * the batch reference (payment information / settlement id) and the number of transactions of a batch booking.
     */
    public record Details(String instructionId, String uetr, String counterpartyAccount, String transactionCode,
                          boolean reversal, BigDecimal charges, BigDecimal originalAmount, String originalCurrency,
                          String batchRef, Integer batchCount) {
        public static final Details NONE = new Details(null, null, null, null, false, null, null, null, null, null);
    }
}
