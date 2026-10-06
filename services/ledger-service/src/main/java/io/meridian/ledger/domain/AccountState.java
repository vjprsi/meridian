package io.meridian.ledger.domain;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A ledger account with its balance row, locked for the duration of a posting.
 * Balances are kept in normal-balance terms: a credit raises a CREDIT-normal account (e.g. a customer deposit).
 */
public final class AccountState {
    public final UUID id;
    public final UUID ledgerId;
    public final String currency;
    public final String normalBalance;
    public final String status;
    public final boolean allowNegative;
    public final BigDecimal overdraftLimit;
    public final BigDecimal minBalance;
    public BigDecimal postedBalance;
    public BigDecimal postedDebits;
    public BigDecimal postedCredits;
    public BigDecimal pendingDebits;
    public BigDecimal pendingCredits;
    public long version;
    public boolean changed;

    public AccountState(UUID id, UUID ledgerId, String currency, String normalBalance, String status,
                        boolean allowNegative, BigDecimal overdraftLimit, BigDecimal minBalance,
                        BigDecimal postedBalance, BigDecimal postedDebits, BigDecimal postedCredits,
                        BigDecimal pendingDebits, BigDecimal pendingCredits, long version) {
        this.id = id;
        this.ledgerId = ledgerId;
        this.currency = currency;
        this.normalBalance = normalBalance;
        this.status = status;
        this.allowNegative = allowNegative;
        this.overdraftLimit = overdraftLimit;
        this.minBalance = minBalance;
        this.postedBalance = postedBalance;
        this.postedDebits = postedDebits;
        this.postedCredits = postedCredits;
        this.pendingDebits = pendingDebits;
        this.pendingCredits = pendingCredits;
        this.version = version;
    }

    /** Spendable funds: posted balance minus reserved holds plus any arranged overdraft. */
    public BigDecimal available() {
        return postedBalance.subtract(pendingDebits).add(overdraftLimit);
    }

    /** Applies one line; returns the new posted balance. Enforces status and funds rules. */
    public BigDecimal apply(PostingLine line) {
        if (!currency.equals(line.currency())) {
            throw ApiException.unprocessable("currency_mismatch",
                    "Account " + id + " is in " + currency + ", line is in " + line.currency());
        }
        checkStatus(line.direction());
        boolean increases = line.direction().name().equals(normalBalance);
        BigDecimal delta = increases ? line.amount() : line.amount().negate();
        if (line.direction() == PostingLine.Direction.DEBIT) {
            postedDebits = postedDebits.add(line.amount());
        } else {
            postedCredits = postedCredits.add(line.amount());
        }
        postedBalance = postedBalance.add(delta);
        if (delta.signum() < 0 && !allowNegative && available().compareTo(minBalance) < 0) {
            throw ApiException.unprocessable("insufficient_funds",
                    "Account " + id + " has insufficient available balance");
        }
        version++;
        changed = true;
        return postedBalance;
    }

    /** Reserves funds for a hold (reduces available balance). */
    public void reserve(BigDecimal amount) {
        checkStatus(outgoingDirection());
        pendingDebits = pendingDebits.add(amount);
        if (!allowNegative && available().compareTo(minBalance) < 0) {
            throw ApiException.unprocessable("insufficient_funds",
                    "Account " + id + " has insufficient available balance for the hold");
        }
        changed = true;
    }

    public void unreserve(BigDecimal amount) {
        pendingDebits = pendingDebits.subtract(amount).max(BigDecimal.ZERO);
        changed = true;
    }

    /** The direction that takes money out of this account (debit for a liability such as a deposit). */
    public PostingLine.Direction outgoingDirection() {
        return "CREDIT".equals(normalBalance) ? PostingLine.Direction.DEBIT : PostingLine.Direction.CREDIT;
    }

    private void checkStatus(PostingLine.Direction direction) {
        switch (status) {
            case "ACTIVE" -> { }
            case "FROZEN", "CLOSED" -> throw ApiException.unprocessable("account_" + status.toLowerCase(),
                    "Account " + id + " is " + status.toLowerCase());
            case "FROZEN_DEBIT" -> {
                if (direction == PostingLine.Direction.DEBIT) {
                    throw ApiException.unprocessable("account_frozen", "Account " + id + " is frozen for debits");
                }
            }
            case "FROZEN_CREDIT" -> {
                if (direction == PostingLine.Direction.CREDIT) {
                    throw ApiException.unprocessable("account_frozen", "Account " + id + " is frozen for credits");
                }
            }
            default -> throw ApiException.unprocessable("account_unavailable", "Account " + id + " is " + status);
        }
    }
}
