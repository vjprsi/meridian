package io.meridian.deposit.domain;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Sweep decisions. Balances are posted balances in normal-balance terms (positive = funds held for the customer).
 * <ul>
 *   <li>TARGET_BALANCE: keep {@code target_balance} on the source; excess goes out, shortfall is pulled back
 *       from the target (up to what the target holds).</li>
 *   <li>THRESHOLD_OUT: when the source exceeds {@code threshold_amount}, sweep down to {@code target_balance}
 *       (default: the threshold).</li>
 *   <li>THRESHOLD_IN: when the source falls below {@code threshold_amount}, top it up to {@code target_balance}
 *       (default: the threshold) from the target.</li>
 *   <li>ZERO_BALANCE: bring the source to zero in either direction.</li>
 *   <li>CONCENTRATION: move everything above {@code target_balance} (default 0) to the target; never pulls back.</li>
 * </ul>
 * Amounts below {@code min_sweep_amount} are skipped.
 */
public final class SweepCalculator {

    public enum Direction { TO_TARGET, FROM_TARGET, NONE }

    public record Decision(Direction direction, BigDecimal amount, String reason) {
        static Decision none(String reason) {
            return new Decision(Direction.NONE, BigDecimal.ZERO, reason);
        }
    }

    private SweepCalculator() {}

    public static Decision decide(String sweepType, BigDecimal source, BigDecimal target, BigDecimal threshold,
                                  BigDecimal targetBalance, BigDecimal minSweep) {
        BigDecimal min = minSweep == null ? BigDecimal.ZERO : minSweep;
        Decision d = switch (sweepType.toUpperCase(Locale.ROOT)) {
            case "TARGET_BALANCE" -> {
                BigDecimal keep = require(targetBalance, "target_balance");
                yield toward(source, keep, target);
            }
            case "THRESHOLD_OUT" -> {
                BigDecimal t = require(threshold, "threshold_amount");
                if (source.compareTo(t) <= 0) {
                    yield Decision.none("source balance is not above the threshold");
                }
                BigDecimal keep = targetBalance == null ? t : targetBalance;
                yield new Decision(Direction.TO_TARGET, source.subtract(keep).max(BigDecimal.ZERO), "above threshold");
            }
            case "THRESHOLD_IN" -> {
                BigDecimal t = require(threshold, "threshold_amount");
                if (source.compareTo(t) >= 0) {
                    yield Decision.none("source balance is not below the threshold");
                }
                BigDecimal fill = targetBalance == null ? t : targetBalance;
                BigDecimal need = fill.subtract(source);
                yield new Decision(Direction.FROM_TARGET, need.min(target.max(BigDecimal.ZERO)), "below threshold");
            }
            case "ZERO_BALANCE" -> toward(source, BigDecimal.ZERO, target);
            case "CONCENTRATION" -> {
                BigDecimal keep = targetBalance == null ? BigDecimal.ZERO : targetBalance;
                if (source.compareTo(keep) <= 0) {
                    yield Decision.none("nothing above the retained balance");
                }
                yield new Decision(Direction.TO_TARGET, source.subtract(keep), "concentration");
            }
            default -> throw new IllegalArgumentException("unknown sweep type " + sweepType);
        };
        if (d.direction() == Direction.NONE) {
            return d;
        }
        if (d.amount().signum() <= 0) {
            return Decision.none("nothing to move");
        }
        if (d.amount().compareTo(min) < 0) {
            return Decision.none("amount " + d.amount().toPlainString() + " is below the minimum sweep amount");
        }
        return d;
    }

    private static Decision toward(BigDecimal source, BigDecimal keep, BigDecimal target) {
        int c = source.compareTo(keep);
        if (c > 0) {
            return new Decision(Direction.TO_TARGET, source.subtract(keep), "excess over target balance");
        }
        if (c < 0) {
            BigDecimal available = target.max(BigDecimal.ZERO);
            return new Decision(Direction.FROM_TARGET, keep.subtract(source).min(available), "shortfall below target balance");
        }
        return Decision.none("source already at target balance");
    }

    private static BigDecimal require(BigDecimal v, String field) {
        if (v == null) {
            throw new IllegalArgumentException(field + " is required for this sweep type");
        }
        return v;
    }
}
