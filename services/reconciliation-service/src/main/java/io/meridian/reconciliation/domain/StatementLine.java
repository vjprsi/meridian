package io.meridian.reconciliation.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/** One parsed external line (statement or settlement record) before it is stored. */
public record StatementLine(String externalRef, String endToEndId, LocalDate valueDate, LocalDate bookingDate,
                            Direction direction, BigDecimal amount, String currency, String counterparty,
                            String narrative, Map<String, String> raw) {

    public enum Direction { DEBIT, CREDIT }
}
