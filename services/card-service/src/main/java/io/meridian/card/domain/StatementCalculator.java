package io.meridian.card.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

/** Credit card statement arithmetic from the card account's ledger entries of one cycle. */
public final class StatementCalculator {
    private StatementCalculator() {}

    public static final BigDecimal MIN_PAYMENT_FLOOR = new BigDecimal("25.00");
    public static final BigDecimal MIN_PAYMENT_RATE = new BigDecimal("0.05");

    /** One ledger line: DEBIT increases what the cardholder owes, CREDIT is a payment / refund. */
    public record Line(String direction, BigDecimal amount, String description) {}

    public record Statement(BigDecimal opening, BigDecimal purchases, BigDecimal cashAdvances, BigDecimal fees,
                            BigDecimal interest, BigDecimal payments, BigDecimal closing, BigDecimal minimumDue) {}

    public static Statement compute(BigDecimal opening, List<Line> lines) {
        BigDecimal purchases = BigDecimal.ZERO;
        BigDecimal cash = BigDecimal.ZERO;
        BigDecimal fees = BigDecimal.ZERO;
        BigDecimal interest = BigDecimal.ZERO;
        BigDecimal payments = BigDecimal.ZERO;
        for (Line l : lines) {
            String d = l.description() == null ? "" : l.description().toLowerCase(Locale.ROOT);
            if ("CREDIT".equals(l.direction())) {
                payments = payments.add(l.amount());
            } else if (word(d, "interest")) {
                interest = interest.add(l.amount());
            } else if (word(d, "fee") || word(d, "fees")) {
                fees = fees.add(l.amount());
            } else if (word(d, "atm") || word(d, "cash")) {
                cash = cash.add(l.amount());
            } else {
                purchases = purchases.add(l.amount());
            }
        }
        BigDecimal closing = opening.add(purchases).add(cash).add(fees).add(interest).subtract(payments);
        return new Statement(opening, purchases, cash, fees, interest, payments, closing, minimumDue(closing));
    }

    private static boolean word(String text, String w) {
        return java.util.regex.Pattern.compile("\\b" + w + "\\b").matcher(text).find();
    }

    public static BigDecimal minimumDue(BigDecimal closing) {
        if (closing.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        BigDecimal pct = closing.multiply(MIN_PAYMENT_RATE).setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal due = pct.max(MIN_PAYMENT_FLOOR);
        return due.min(closing).setScale(2, RoundingMode.HALF_EVEN);
    }
}
