package io.meridian.gl.domain;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Double-entry rules for GL journals: at least two positive lines that balance per currency. */
public final class JournalRules {
    private static final Pattern CCY = Pattern.compile("[A-Z]{3}");

    private JournalRules() {}

    /** Validates the lines and returns the total debits per currency. */
    public static Map<String, BigDecimal> validate(List<JournalLine> lines) {
        if (lines == null || lines.size() < 2) {
            throw ApiException.unprocessable("journal_lines_required", "A GL journal needs at least two lines");
        }
        Map<String, BigDecimal> net = new TreeMap<>();
        Map<String, BigDecimal> debits = new TreeMap<>();
        for (JournalLine l : lines) {
            if (l.glAccountId() == null || l.direction() == null) {
                throw ApiException.unprocessable("invalid_journal_line", "Each line needs gl_account_id and direction");
            }
            if (l.amount() == null || l.amount().signum() <= 0) {
                throw ApiException.unprocessable("invalid_amount", "Line amounts must be positive");
            }
            if (l.baseAmount() == null || l.baseAmount().signum() < 0) {
                throw ApiException.unprocessable("invalid_amount", "Line base amounts must not be negative");
            }
            if (l.currency() == null || !CCY.matcher(l.currency()).matches()) {
                throw ApiException.unprocessable("invalid_currency", "Line currency must be an ISO 4217 code");
            }
            net.merge(l.currency(), l.signedAmount(), BigDecimal::add);
            if (l.direction() == JournalLine.Direction.DEBIT) {
                debits.merge(l.currency(), l.amount(), BigDecimal::add);
            }
        }
        net.forEach((ccy, n) -> {
            if (n.signum() != 0) {
                throw ApiException.unprocessable("unbalanced_journal",
                        "Debits and credits differ by " + n.abs().toPlainString() + " " + ccy);
            }
        });
        return debits;
    }
}
