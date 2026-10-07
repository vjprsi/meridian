package io.meridian.reconciliation.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Result of parsing a statement file: identification (statement id, sequence, account) and header balances when the
 * format has them, declared control totals (MT942 :90D: / :90C:), and its lines.
 */
public record ParsedStatement(LocalDate statementDate, BigDecimal openingBalance, BigDecimal closingBalance,
                              List<StatementLine> lines, String statementId, String sequenceNo, String accountId,
                              BigDecimal declaredDebits, BigDecimal declaredCredits) {

    public ParsedStatement(LocalDate statementDate, BigDecimal openingBalance, BigDecimal closingBalance,
                           List<StatementLine> lines) {
        this(statementDate, openingBalance, closingBalance, lines, null, null, null, null, null);
    }

    /** Net of the lines: credits minus debits. */
    public BigDecimal movements() {
        BigDecimal net = BigDecimal.ZERO;
        for (StatementLine l : lines) {
            net = l.direction() == StatementLine.Direction.CREDIT ? net.add(l.amount()) : net.subtract(l.amount());
        }
        return net;
    }

    /**
     * Checks the lines against the stated figures: opening + movements = closing when both balances are known, and
     * the sums of debits / credits against declared totals. Throws {@link ParseException} naming the difference.
     */
    public void checkTotals(BigDecimal opening, BigDecimal closing) {
        BigDecimal net = movements();
        if (opening != null && closing != null && opening.add(net).compareTo(closing) != 0) {
            throw new ParseException("Balance check failed: opening " + opening.toPlainString()
                    + " + movements " + net.toPlainString() + " != closing " + closing.toPlainString());
        }
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (StatementLine l : lines) {
            if (l.direction() == StatementLine.Direction.CREDIT) {
                credits = credits.add(l.amount());
            } else {
                debits = debits.add(l.amount());
            }
        }
        if (declaredDebits != null && declaredDebits.compareTo(debits) != 0) {
            throw new ParseException("Control total failed: declared debits " + declaredDebits.toPlainString()
                    + " != sum of debit lines " + debits.toPlainString());
        }
        if (declaredCredits != null && declaredCredits.compareTo(credits) != 0) {
            throw new ParseException("Control total failed: declared credits " + declaredCredits.toPlainString()
                    + " != sum of credit lines " + credits.toPlainString());
        }
    }

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
