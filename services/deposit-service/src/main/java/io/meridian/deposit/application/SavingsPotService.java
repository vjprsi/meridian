package io.meridian.deposit.application;

import static io.meridian.deposit.infrastructure.Rows.bool;
import static io.meridian.deposit.infrastructure.Rows.date;
import static io.meridian.deposit.infrastructure.Rows.dec;
import static io.meridian.deposit.infrastructure.Rows.decOr0;
import static io.meridian.deposit.infrastructure.Rows.str;
import static io.meridian.deposit.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.deposit.config.DepositProperties;
import io.meridian.deposit.domain.Currencies;
import io.meridian.deposit.domain.Lifecycle;
import io.meridian.deposit.domain.Schedule;
import io.meridian.deposit.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Savings pots: ring-fenced balances held in a ledger sub-account of the parent deposit account. Deposits and
 * withdrawals are ledger transfers between the parent's and the pot's ledger accounts; the pot's {@code balance}
 * column is a cache of the ledger. Includes validation and scheduled execution of automatic saving rules.
 */
@Service
public class SavingsPotService {
    static final String TABLE = "savings_pot";

    private final Rows rows;
    private final AccountSupport accounts;
    private final LedgerPort ledger;
    private final Events events;
    private final DepositProperties props;

    public SavingsPotService(Rows rows, AccountSupport accounts, LedgerPort ledger, Events events, DepositProperties props) {
        this.rows = rows;
        this.accounts = accounts;
        this.ledger = ledger;
        this.events = events;
        this.props = props;
    }

    @Transactional
    public ObjectNode create(Object body) {
        ObjectNode v = rows.columns(TABLE, body);
        UUID accountId = uuid(v, "deposit_account_id");
        accounts.reference(accountId, "deposit_account_id");
        ObjectNode acc = accounts.lock(accountId);
        if (!"ACTIVE".equals(str(acc, "status"))) {
            throw ApiException.conflict("account_not_active", "Pots can only be opened on ACTIVE accounts");
        }
        accounts.requireCurrency(acc, str(v, "currency"));
        BigDecimal goal = dec(v, "goal_amount");
        if (goal != null && (goal.signum() <= 0 || !Currencies.fitsMinorUnit(goal, str(v, "currency")))) {
            throw ApiException.unprocessable("invalid_goal", "goal_amount must be a positive amount");
        }
        LocalDate goalDate = date(v, "goal_date");
        if (goalDate != null && !goalDate.isAfter(accounts.today())) {
            throw ApiException.unprocessable("invalid_goal_date", "goal_date must be in the future");
        }
        if (bool(v, "is_locked") && goalDate == null) {
            throw ApiException.unprocessable("goal_date_required", "A locked pot needs a goal_date to unlock on");
        }
        long open = rows.scalar("select count(*) from savings_pot where deposit_account_id = ? and status <> 'CLOSED'",
                Long.class, accountId).orElse(0L);
        if (open >= props.maxPotsPerAccount()) {
            throw ApiException.unprocessable("too_many_pots", "An account can have at most " + props.maxPotsPerAccount()
                    + " open pots");
        }
        UUID id = Ids.newId();
        v.put("id", id.toString());
        v.put("status", "ACTIVE");
        v.put("balance", "0");
        v.put("ledger_account_id", ledger.openAccount(LedgerPort.key("pot-ledger-account", id, null), "POT-" + id,
                "Pot " + str(v, "name"), str(v, "currency"), BigDecimal.ZERO, accounts.requireLedger(acc), "SAVINGS_POT",
                id).toString());
        return rows.insert(TABLE, v);
    }

    @Transactional
    public ObjectNode deposit(UUID potId, BigDecimal amount, String clientKey) {
        return moveIn(potId, amount, LedgerPort.key("pot-deposit", potId, clientKey), "Transfer to pot");
    }

    private ObjectNode moveIn(UUID potId, BigDecimal amount, String key, String narrative) {
        ObjectNode pot = rows.lock(TABLE, potId);
        Lifecycle.SAVINGS_POT.requireIn(str(pot, "status"), "deposit into", "ACTIVE", "GOAL_REACHED", "LOCKED");
        String ccy = str(pot, "currency");
        requireAmount(amount, ccy);
        ObjectNode acc = accounts.lock(uuid(pot, "deposit_account_id"));
        accounts.requireDebitAllowed(acc);
        ledger.post(new LedgerPort.Posting(key, "TRANSFER", narrative + " " + str(pot, "name"), ccy, "SAVINGS_POT", potId,
                null, List.of(LedgerPort.Leg.debit(accounts.requireLedger(acc), amount, narrative),
                        LedgerPort.Leg.credit(uuid(pot, "ledger_account_id"), amount, narrative))));
        BigDecimal balance = decOr0(pot, "balance").add(amount);
        ObjectNode v = rows.object();
        v.put("balance", balance.toPlainString());
        BigDecimal goal = dec(pot, "goal_amount");
        boolean reached = goal != null && balance.compareTo(goal) >= 0 && !"GOAL_REACHED".equals(str(pot, "status"));
        if (reached) {
            v.put("status", "GOAL_REACHED");
        }
        ObjectNode updated = rows.update(TABLE, potId, v);
        rows.execute("update deposit_account set last_customer_activity_at = now() where id = ?", uuid(acc, "id"));
        if (reached) {
            events.publish("accounts.savings_pot.goal_reached", TABLE, updated);
        }
        return updated;
    }

    @Transactional
    public ObjectNode withdraw(UUID potId, BigDecimal amount, String clientKey) {
        ObjectNode pot = rows.lock(TABLE, potId);
        Lifecycle.SAVINGS_POT.requireIn(str(pot, "status"), "withdraw from", "ACTIVE", "GOAL_REACHED");
        requireUnlocked(pot);
        String ccy = str(pot, "currency");
        requireAmount(amount, ccy);
        BigDecimal balance = decOr0(pot, "balance");
        if (amount.compareTo(balance) > 0) {
            throw ApiException.unprocessable("insufficient_funds", "The pot holds only " + balance.toPlainString());
        }
        ObjectNode acc = accounts.lock(uuid(pot, "deposit_account_id"));
        accounts.requireCreditAllowed(acc);
        ledger.post(new LedgerPort.Posting(LedgerPort.key("pot-withdraw", potId, clientKey), "TRANSFER",
                "Transfer from pot " + str(pot, "name"), ccy, "SAVINGS_POT", potId, null, List.of(
                        LedgerPort.Leg.debit(uuid(pot, "ledger_account_id"), amount, "Transfer from pot"),
                        LedgerPort.Leg.credit(accounts.requireLedger(acc), amount, "Transfer from pot"))));
        BigDecimal after = balance.subtract(amount);
        ObjectNode v = rows.object();
        v.put("balance", after.toPlainString());
        BigDecimal goal = dec(pot, "goal_amount");
        if ("GOAL_REACHED".equals(str(pot, "status")) && goal != null && after.compareTo(goal) < 0) {
            v.put("status", "ACTIVE");
        }
        rows.execute("update deposit_account set last_customer_activity_at = now() where id = ?", uuid(acc, "id"));
        return rows.update(TABLE, potId, v);
    }

    @Transactional
    public ObjectNode close(UUID potId, String clientKey) {
        ObjectNode pot = rows.lock(TABLE, potId);
        Lifecycle.SAVINGS_POT.require(str(pot, "status"), "CLOSED");
        requireUnlocked(pot);
        ObjectNode acc = accounts.lock(uuid(pot, "deposit_account_id"));
        UUID potLedger = uuid(pot, "ledger_account_id");
        BigDecimal balance = LedgerPort.posted(ledger.balance(potLedger)); // ledger is the source of truth
        if (balance.signum() > 0) {
            accounts.requireCreditAllowed(acc);
            ledger.post(new LedgerPort.Posting(LedgerPort.key("pot-close", potId, null), "TRANSFER",
                    "Pot " + str(pot, "name") + " closed", str(pot, "currency"), "SAVINGS_POT", potId, null, List.of(
                            LedgerPort.Leg.debit(potLedger, balance, "Pot closed"),
                            LedgerPort.Leg.credit(accounts.requireLedger(acc), balance, "Pot closed"))));
        }
        ledger.restrict(potLedger, "ACTIVE", "CLOSED", "Pot closed", LedgerPort.key("pot-close-account", potId, null));
        ObjectNode v = rows.object();
        v.put("status", "CLOSED");
        v.put("balance", "0");
        v.put("closed_at", accounts.now().toString());
        rows.execute("update savings_pot_rule set is_active = false where savings_pot_id = ?", potId);
        return rows.update(TABLE, potId, v);
    }

    private static void requireAmount(BigDecimal amount, String ccy) {
        if (amount == null || amount.signum() <= 0 || !Currencies.fitsMinorUnit(amount, ccy)) {
            throw ApiException.unprocessable("invalid_amount", "amount must be positive with at most "
                    + Currencies.digits(ccy) + " decimals");
        }
    }

    private void requireUnlocked(JsonNode pot) {
        LocalDate goalDate = date(pot, "goal_date");
        if (bool(pot, "is_locked") && (goalDate == null || accounts.today().isBefore(goalDate))) {
            throw ApiException.unprocessable("pot_locked", "The pot is locked until " + goalDate);
        }
    }

    // ------------------------------------------------------------------ rules

    @Transactional
    public ObjectNode createRule(Object body) {
        ObjectNode v = rows.columns("savings_pot_rule", body);
        UUID potId = uuid(v, "savings_pot_id");
        ObjectNode pot = rows.find(TABLE, potId).orElseThrow(() -> ApiException.unprocessable("pot_not_found",
                "savings_pot_id " + potId + " does not reference an existing pot"));
        if ("CLOSED".equals(str(pot, "status"))) {
            throw ApiException.conflict("pot_closed", "Rules cannot be added to a closed pot");
        }
        validateRule(v, str(pot, "currency"));
        if ("FIXED_SCHEDULE".equals(str(v, "rule_type"))) {
            v.put("next_run_date", accounts.today().plusDays(1).toString());
        }
        return rows.insert("savings_pot_rule", v);
    }

    public static void validateRule(JsonNode v, String ccy) {
        String type = str(v, "rule_type");
        BigDecimal amount = dec(v, "amount");
        BigDecimal pct = dec(v, "percentage");
        Integer mult = v.hasNonNull("multiplier") ? v.get("multiplier").asInt() : null;
        switch (type) {
            case "ROUND_UP" -> {
                if (mult != null && (mult < 1 || mult > 10)) {
                    throw ApiException.unprocessable("invalid_multiplier", "multiplier must be 1-10");
                }
            }
            case "ROUND_UP_MULTIPLIER" -> {
                if (mult == null || mult < 2 || mult > 10) {
                    throw ApiException.unprocessable("invalid_multiplier", "ROUND_UP_MULTIPLIER needs a multiplier of 2-10");
                }
            }
            case "PERCENT_OF_INCOME", "SALARY_SPLIT" -> {
                if ((pct == null || pct.signum() <= 0 || pct.compareTo(BigDecimal.ONE) > 0)
                        && !("SALARY_SPLIT".equals(type) && amount != null && amount.signum() > 0)) {
                    throw ApiException.unprocessable("invalid_percentage", type + " needs a percentage in (0, 1]");
                }
            }
            case "FIXED_SCHEDULE" -> {
                if (amount == null || amount.signum() <= 0 || !Currencies.fitsMinorUnit(amount, ccy)) {
                    throw ApiException.unprocessable("invalid_amount", "FIXED_SCHEDULE needs a positive amount");
                }
                if (!v.hasNonNull("frequency") || "ONCE".equals(str(v, "frequency"))) {
                    throw ApiException.unprocessable("frequency_required", "FIXED_SCHEDULE needs a recurring frequency");
                }
            }
            default -> throw ApiException.unprocessable("invalid_rule_type", "Unknown rule type " + type);
        }
    }

    /** Runs FIXED_SCHEDULE rules due on or before {@code asOf}; failures (e.g. insufficient funds) skip a run. */
    @Transactional
    public int runScheduledRules(LocalDate asOf) {
        int runs = 0;
        for (ObjectNode rule : rows.query("""
                select row_to_json(r)::text from savings_pot_rule r where rule_type = 'FIXED_SCHEDULE' and is_active
                  and next_run_date <= ? order by id for update""", asOf)) {
            UUID ruleId = uuid(rule, "id");
            LocalDate due = date(rule, "next_run_date");
            try {
                moveIn(uuid(rule, "savings_pot_id"), dec(rule, "amount"),
                        LedgerPort.key("pot-rule", ruleId, due.toString()), "Scheduled saving");
                runs++;
            } catch (ApiException e) {
                if (e.status() >= 500) {
                    throw e;
                }
            }
            ObjectNode v = rows.object();
            v.put("next_run_date", Schedule.advance(due, Schedule.frequency(str(rule, "frequency"))).toString());
            rows.update("savings_pot_rule", ruleId, v);
        }
        return runs;
    }
}
