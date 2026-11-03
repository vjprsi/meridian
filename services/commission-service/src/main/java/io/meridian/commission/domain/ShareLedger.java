package io.meridian.commission.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * WE-PRICE-1 (PE-14): the balanced Commission-Payable / Commission-Receivable (CP/CR) journal for a commission-sharing
 * split. The total shared commission is recognised as a Receivable (debit, due from the funding source) against a
 * Payable (credit, owed to the beneficiaries). Pure so the balance can be unit-tested without the ledger.
 */
public final class ShareLedger {
    private ShareLedger() {}

    public record Line(UUID account, String direction, BigDecimal amount) {}

    /** A deterministic share-group id derived from the source transaction (stable across retries). */
    public static UUID shareGroupId(UUID walletTransactionId) {
        return UUID.nameUUIDFromBytes(("share:" + walletTransactionId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** The CP/CR entries for a total: debit Commission Receivable, credit Commission Payable. */
    public static List<Line> cpcr(UUID receivableAccount, UUID payableAccount, BigDecimal total) {
        return List.of(new Line(receivableAccount, "DEBIT", total), new Line(payableAccount, "CREDIT", total));
    }

    /** True when the debit and credit totals are equal (a balanced journal). */
    public static boolean balanced(List<Line> lines) {
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (Line l : lines) {
            if ("DEBIT".equals(l.direction())) {
                debit = debit.add(l.amount());
            } else {
                credit = credit.add(l.amount());
            }
        }
        return debit.compareTo(credit) == 0;
    }
}
