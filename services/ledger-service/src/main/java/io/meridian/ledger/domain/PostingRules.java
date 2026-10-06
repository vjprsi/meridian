package io.meridian.ledger.domain;

import io.meridian.common.error.ApiException;
import io.meridian.common.money.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Invariants every posting must satisfy before any account is touched. Pure, no I/O. */
public final class PostingRules {
    public static final int MAX_LINES = 500;

    private PostingRules() {}

    public static void validate(List<PostingLine> lines) {
        if (lines == null || lines.size() < 2) {
            throw ApiException.unprocessable("unbalanced_entry", "A journal entry needs at least two lines");
        }
        if (lines.size() > MAX_LINES) {
            throw ApiException.unprocessable("too_many_lines", "A journal entry may have at most " + MAX_LINES + " lines");
        }
        Map<String, BigDecimal> net = new TreeMap<>();
        for (int i = 0; i < lines.size(); i++) {
            PostingLine l = lines.get(i);
            if (l.accountId() == null || l.direction() == null || l.amount() == null || l.currency() == null) {
                throw ApiException.badRequest("invalid_line", "Line " + (i + 1) + " needs account, direction, amount and currency");
            }
            if (l.amount().signum() <= 0) {
                throw ApiException.unprocessable("invalid_amount", "Line " + (i + 1) + ": amount must be positive");
            }
            new Money(l.amount(), l.currency()); // validates currency code and scale
            BigDecimal signed = l.direction() == PostingLine.Direction.DEBIT ? l.amount() : l.amount().negate();
            net.merge(l.currency(), signed, BigDecimal::add);
        }
        net.forEach((ccy, diff) -> {
            if (diff.signum() != 0) {
                throw ApiException.unprocessable("unbalanced_entry",
                        "Debits and credits differ by " + diff.toPlainString() + " " + ccy);
            }
        });
    }
}
