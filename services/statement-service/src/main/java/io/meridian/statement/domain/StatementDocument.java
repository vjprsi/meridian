package io.meridian.statement.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.List;

/**
 * A statement frozen at generation time: header, balances and lines. Everything a renderer needs; rendering is
 * deterministic so the stored checksum can be re-verified on every download.
 */
public record StatementDocument(String statementId, int statementNo, String accountNumber, String iban, String currency,
                                LocalDate periodStart, LocalDate periodEnd, BigDecimal openingBalance,
                                BigDecimal totalCredits, BigDecimal totalDebits, BigDecimal closingBalance,
                                BigDecimal interestEarned, BigDecimal feesCharged, OffsetDateTime generatedAt,
                                String language, List<Line> lines) {

    /** One booked movement. {@code direction} is CREDIT (money in) or DEBIT (money out). */
    public record Line(int lineNo, LocalDate bookingDate, LocalDate valueDate, String description, String reference,
                       String counterparty, String direction, BigDecimal amount, BigDecimal runningBalance,
                       String ledgerEntryId, String transactionCode) {}

    /** Totals of a statement computed from its opening balance and lines. */
    public record Totals(BigDecimal credits, BigDecimal debits, BigDecimal closing) {}

    public static Totals totals(BigDecimal opening, List<Line> lines) {
        BigDecimal credits = BigDecimal.ZERO;
        BigDecimal debits = BigDecimal.ZERO;
        for (Line l : lines) {
            if ("CREDIT".equals(l.direction())) {
                credits = credits.add(l.amount());
            } else {
                debits = debits.add(l.amount());
            }
        }
        return new Totals(credits, debits, opening.add(credits).subtract(debits));
    }

    /** Running balances must chain: previous balance +/- amount = this line's running balance. */
    public static boolean chained(BigDecimal opening, List<Line> lines) {
        BigDecimal running = opening;
        for (Line l : lines) {
            running = "CREDIT".equals(l.direction()) ? running.add(l.amount()) : running.subtract(l.amount());
            if (running.compareTo(l.runningBalance()) != 0) {
                return false;
            }
        }
        return true;
    }

    /** Amount formatted to the currency's minor unit. */
    public String money(BigDecimal amount) {
        int digits;
        try {
            digits = Math.max(0, Currency.getInstance(currency).getDefaultFractionDigits());
        } catch (IllegalArgumentException e) {
            digits = 2;
        }
        return (amount == null ? BigDecimal.ZERO : amount).setScale(digits, RoundingMode.HALF_EVEN).toPlainString();
    }
}
