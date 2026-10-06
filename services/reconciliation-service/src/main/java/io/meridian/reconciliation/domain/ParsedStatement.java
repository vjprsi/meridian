package io.meridian.reconciliation.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Result of parsing a statement file: header balances (when the format has them) and its lines. */
public record ParsedStatement(LocalDate statementDate, BigDecimal openingBalance, BigDecimal closingBalance,
                              List<StatementLine> lines) {

    /** A file that cannot be parsed; the message names the line or element at fault. */
    public static class ParseException extends RuntimeException {
        public ParseException(String message) {
            super(message);
        }

        public ParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
