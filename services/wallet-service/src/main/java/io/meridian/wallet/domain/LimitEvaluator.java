package io.meridian.wallet.domain;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * KYC-tier limit checks. A tier limit row applies to one transaction type or (txn_type empty) to all debits of the
 * wallet; usage counts debits that are completed or still pending (holds) in the current UTC day / month.
 */
public final class LimitEvaluator {
    private LimitEvaluator() {}

    public record Limit(UUID id, String txnType, BigDecimal perTxnMin, BigDecimal perTxnMax, BigDecimal maxBalance,
                        BigDecimal dailyAmount, Integer dailyCount, BigDecimal monthlyAmount, Integer monthlyCount) {}

    public record Usage(BigDecimal dailyAmount, int dailyCount, BigDecimal monthlyAmount, int monthlyCount) {
        public static final Usage NONE = new Usage(BigDecimal.ZERO, 0, BigDecimal.ZERO, 0);
    }

    public record Violation(String code, String message) {}

    /** First violated rule for a debit of {@code amount} given the usage so far, if any. */
    public static Optional<Violation> checkDebit(Limit l, Usage u, BigDecimal amount) {
        String scope = l.txnType() == null ? "all transactions" : l.txnType();
        if (l.perTxnMin() != null && amount.compareTo(l.perTxnMin()) < 0) {
            return Optional.of(new Violation("amount_below_minimum",
                    "Amount is below the minimum " + l.perTxnMin().toPlainString() + " for " + scope));
        }
        if (l.perTxnMax() != null && amount.compareTo(l.perTxnMax()) > 0) {
            return Optional.of(new Violation("limit_exceeded",
                    "Amount exceeds the per-transaction limit " + l.perTxnMax().toPlainString() + " for " + scope));
        }
        if (l.dailyAmount() != null && u.dailyAmount().add(amount).compareTo(l.dailyAmount()) > 0) {
            return Optional.of(new Violation("limit_exceeded",
                    "Daily limit " + l.dailyAmount().toPlainString() + " for " + scope + " would be exceeded"));
        }
        if (l.dailyCount() != null && u.dailyCount() + 1 > l.dailyCount()) {
            return Optional.of(new Violation("limit_exceeded",
                    "Daily transaction count " + l.dailyCount() + " for " + scope + " reached"));
        }
        if (l.monthlyAmount() != null && u.monthlyAmount().add(amount).compareTo(l.monthlyAmount()) > 0) {
            return Optional.of(new Violation("limit_exceeded",
                    "Monthly limit " + l.monthlyAmount().toPlainString() + " for " + scope + " would be exceeded"));
        }
        if (l.monthlyCount() != null && u.monthlyCount() + 1 > l.monthlyCount()) {
            return Optional.of(new Violation("limit_exceeded",
                    "Monthly transaction count " + l.monthlyCount() + " for " + scope + " reached"));
        }
        return Optional.empty();
    }

    /** Violation when a credit would push the wallet balance above the tier's maximum balance. */
    public static Optional<Violation> checkBalance(Limit l, BigDecimal balance, BigDecimal credit) {
        if (l.maxBalance() != null && balance.add(credit).compareTo(l.maxBalance()) > 0) {
            return Optional.of(new Violation("max_balance_exceeded",
                    "The payee wallet would exceed its maximum balance " + l.maxBalance().toPlainString()));
        }
        return Optional.empty();
    }

    /** Remaining headroom (never negative); null when unlimited. */
    public static BigDecimal remaining(BigDecimal limit, BigDecimal used) {
        return limit == null ? null : limit.subtract(used).max(BigDecimal.ZERO);
    }
}
