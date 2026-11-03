package io.meridian.agentnetwork.domain;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * WE-FLOAT-1 pure business rules for operator stock / e-money float (MQ-STK-1..14). The authoritative DEBIT/CREDIT
 * directions of the FDD §10 fund-flow matrix live here as account <em>roles</em>; the concrete ledger-account and
 * wallet ids are per-tenant config resolved in the services. Everything here is deterministic and unit-tested.
 */
public final class StockRules {
    private StockRules() {}

    public static final Set<String> INSTRUMENTS = Set.of("CASH", "CHEQUE", "DD", "BANK_TRANSFER");

    /** Account roles a stock posting leg can name (resolved to real ids by the service). */
    public enum Account { BANK_CLEARING, CAPITAL, ENTITY, TRUST, SUSPENSE, UNCLAIMED, INCOME }

    /** One posting leg: debit one role, credit another, for {@code amount}. */
    public record Leg(Account debit, Account credit) {}

    /** Amount must be positive with at most 2 decimals (same rule as float requests). */
    public static void requireAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2) {
            throw ApiException.unprocessable("invalid_amount", "amount must be positive with at most 2 decimals");
        }
    }

    public static void requireInstrument(String instrument) {
        if (instrument == null || !INSTRUMENTS.contains(instrument)) {
            throw ApiException.unprocessable("invalid_instrument", "instrument must be one of " + INSTRUMENTS);
        }
    }

    /**
     * The ordered posting legs of a stock operation (FDD §10 / MQ-STK-1,4,14):
     * <ul>
     *   <li>STOCK_CREATION: DEBIT bank clearing (1150) / CREDIT capital (IND01) — one leg.</li>
     *   <li>REIMBURSEMENT: DEBIT entity wallet / CREDIT capital, then DEBIT capital / CREDIT bank clearing — two legs.</li>
     * </ul>
     */
    public static List<Leg> legs(String operationType) {
        return switch (operationType) {
            case "STOCK_CREATION" -> List.of(new Leg(Account.BANK_CLEARING, Account.CAPITAL));
            case "REIMBURSEMENT" -> List.of(new Leg(Account.ENTITY, Account.CAPITAL), new Leg(Account.CAPITAL, Account.BANK_CLEARING));
            default -> throw ApiException.unprocessable("invalid_operation_type", "Unknown stock operation type " + operationType);
        };
    }

    /** REIMBURSEMENT needs the entity (business-user) wallet to debit; STOCK_CREATION must not name one. */
    public static void requireEntity(String operationType, Object entityWalletId) {
        boolean reimbursement = "REIMBURSEMENT".equals(operationType);
        if (reimbursement && entityWalletId == null) {
            throw ApiException.unprocessable("entity_wallet_required", "A reimbursement debits the entity wallet; entity_wallet_id is required");
        }
        if (!reimbursement && entityWalletId != null) {
            throw ApiException.unprocessable("entity_wallet_not_allowed", "A stock creation has no entity wallet");
        }
    }

    // ------------------------------------------------------------------ auto-deallocation sweep (MQ-STK-6)

    /**
     * Amount sweepable from a float wallet to trust: everything above the minimum residual, nothing when at or below
     * it. Min-residual precedence (plan value, else {@code agent_float.min_balance}, else the configured default) is
     * resolved by the caller; here it is the single effective residual.
     */
    public static BigDecimal sweepable(BigDecimal balance, BigDecimal minResidual) {
        BigDecimal residual = minResidual == null ? BigDecimal.ZERO : minResidual;
        if (balance == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal over = balance.subtract(residual);
        return over.signum() > 0 ? over : BigDecimal.ZERO;
    }

    public static boolean isCandidate(BigDecimal balance, BigDecimal minResidual) {
        return sweepable(balance, minResidual).signum() > 0;
    }

    /**
     * Effective min residual: the first non-null of the run's value, the wallet's {@code agent_float.min_balance}
     * and the configured default (MQ-STK-6 min-residual precedence).
     */
    public static BigDecimal effectiveResidual(BigDecimal runValue, BigDecimal floatMinBalance, BigDecimal configDefault) {
        if (runValue != null) {
            return runValue;
        }
        if (floatMinBalance != null) {
            return floatMinBalance;
        }
        return configDefault == null ? BigDecimal.ZERO : configDefault;
    }

    // ------------------------------------------------------------------ run status aggregation

    /** Terminal status of a run that moved money on {@code succeeded} of {@code total} lines, {@code failed} of them failing. */
    public static String runStatus(int total, int succeeded, int failed) {
        if (total == 0 || succeeded == 0) {
            return failed > 0 ? "FAILED" : "SUCCEEDED";
        }
        return failed > 0 ? "PARTIALLY_SUCCEEDED" : "SUCCEEDED";
    }

    // ------------------------------------------------------------------ unclaimed settlement (MQ-STK-13)

    /** Final aging stage chosen by {@code settle_to} (CAPITAL default, else INCOME). */
    public static String finalStage(String settleTo) {
        return "INCOME".equalsIgnoreCase(settleTo) ? "UNCLAIMED_TO_INCOME" : "UNCLAIMED_TO_CAPITAL";
    }

    /** The credited account role for a final-stage {@code settle_to}. */
    public static Account finalAccount(String settleTo) {
        return "INCOME".equalsIgnoreCase(settleTo) ? Account.INCOME : Account.CAPITAL;
    }

    /** A GL&lt;-&gt;GL journal balances when its single debit equals its single credit (per currency). */
    public static void requireBalanced(BigDecimal debit, BigDecimal credit) {
        if (debit == null || credit == null || debit.compareTo(credit) != 0) {
            throw ApiException.unprocessable("unbalanced_journal", "A journal entry must balance: debit " + debit + " != credit " + credit);
        }
    }
}
