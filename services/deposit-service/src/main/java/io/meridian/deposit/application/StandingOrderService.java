package io.meridian.deposit.application;

import static io.meridian.deposit.infrastructure.Rows.date;
import static io.meridian.deposit.infrastructure.Rows.dec;
import static io.meridian.deposit.infrastructure.Rows.integer;
import static io.meridian.deposit.infrastructure.Rows.str;
import static io.meridian.deposit.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.deposit.config.DepositProperties;
import io.meridian.deposit.domain.Currencies;
import io.meridian.deposit.domain.Lifecycle;
import io.meridian.deposit.domain.Schedule;
import io.meridian.deposit.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Standing orders: schedule (frequency, day of period, holiday rule, end date / max executions), pause, resume,
 * cancel, skip-next and execution. Internal beneficiaries are paid by a ledger transfer; external payees through
 * the payments clearing account. Failed executions (e.g. insufficient funds) are retried per retry policy and
 * suspend the order after too many consecutive failures.
 */
@Service
public class StandingOrderService {
    static final String TABLE = "standing_order";
    private static final int DEFAULT_MAX_FAILURES = 3;

    private final Rows rows;
    private final AccountSupport accounts;
    private final LedgerPort ledger;
    private final Events events;
    private final DepositProperties props;
    private final Schedule.BusinessCalendar calendar;

    public StandingOrderService(Rows rows, AccountSupport accounts, LedgerPort ledger, Events events,
                                DepositProperties props, Schedule.BusinessCalendar calendar) {
        this.rows = rows;
        this.accounts = accounts;
        this.ledger = ledger;
        this.events = events;
        this.props = props;
        this.calendar = calendar;
    }

    static Schedule.Plan plan(JsonNode so) {
        return new Schedule.Plan(Schedule.frequency(str(so, "frequency")), date(so, "start_date"),
                integer(so, "day_of_period"), date(so, "end_date"), integer(so, "max_executions"),
                Schedule.HolidayRule.parse(str(so, "holiday_rule")));
    }

    @Transactional
    public ObjectNode create(Object body) {
        ObjectNode v = rows.columns(TABLE, body);
        UUID accountId = uuid(v, "deposit_account_id");
        accounts.reference(accountId, "deposit_account_id");
        ObjectNode acc = accounts.lock(accountId);
        accounts.requireDebitAllowed(acc);
        accounts.requireCurrency(acc, str(v, "currency"));
        BigDecimal amount = dec(v, "amount");
        if (amount == null || amount.signum() <= 0 || !Currencies.fitsMinorUnit(amount, str(v, "currency"))) {
            throw ApiException.unprocessable("invalid_amount", "amount must be positive");
        }
        UUID beneficiary = uuid(v, "beneficiary_account_id");
        boolean payee = v.hasNonNull("payee_id");
        if ((beneficiary == null) == !payee) {
            throw ApiException.unprocessable("beneficiary_required", "Give exactly one of payee_id or beneficiary_account_id");
        }
        if (beneficiary != null) {
            if (beneficiary.equals(accountId)) {
                throw ApiException.unprocessable("same_account", "An account cannot pay itself");
            }
            ObjectNode b = accounts.reference(beneficiary, "beneficiary_account_id");
            accounts.requireCurrency(b, str(v, "currency"));
        }
        if (date(v, "start_date").isBefore(accounts.today())) {
            throw ApiException.unprocessable("start_date_in_past", "start_date must be today or later");
        }
        v.put("holiday_rule", Schedule.HolidayRule.parse(str(v, "holiday_rule")).name());
        Schedule.Plan plan = plan(v);
        Schedule.validate(plan);
        LocalDate next = Schedule.nextAfter(plan, calendar, accounts.today().minusDays(1), 0)
                .orElseThrow(() -> ApiException.unprocessable("no_execution_dates", "The schedule produces no execution date"));
        v.put("next_execution_date", next.toString());
        v.put("status", "ACTIVE");
        return rows.insert(TABLE, v);
    }

    @Transactional
    public ObjectNode pause(UUID id, String reason) {
        ObjectNode so = rows.lock(TABLE, id);
        Lifecycle.STANDING_ORDER.require(str(so, "status"), "PAUSED");
        return rows.update(TABLE, id, rows.object().put("status", "PAUSED"));
    }

    @Transactional
    public ObjectNode resume(UUID id) {
        ObjectNode so = rows.lock(TABLE, id);
        Lifecycle.STANDING_ORDER.require(str(so, "status"), "ACTIVE");
        LocalDate today = accounts.today();
        LocalDate pending = date(so, "next_execution_date");
        Optional<LocalDate> next = pending != null && !pending.isBefore(today) ? Optional.of(pending)
                : Schedule.nextAfter(plan(so), calendar, today.minusDays(1), so.path("executions_done").asInt(0));
        ObjectNode v = rows.object();
        v.put("consecutive_failures", 0);
        if (next.isEmpty()) {
            v.put("status", "COMPLETED");
            v.putNull("next_execution_date");
        } else {
            v.put("status", "ACTIVE");
            v.put("next_execution_date", next.get().toString());
        }
        return rows.update(TABLE, id, v);
    }

    @Transactional
    public ObjectNode cancel(UUID id, String reason) {
        ObjectNode so = rows.lock(TABLE, id);
        Lifecycle.STANDING_ORDER.require(str(so, "status"), "CANCELLED");
        ObjectNode v = rows.object();
        v.put("status", "CANCELLED");
        v.putNull("next_execution_date");
        return rows.update(TABLE, id, v);
    }

    @Transactional
    public ObjectNode skipNext(UUID id) {
        ObjectNode so = rows.lock(TABLE, id);
        Lifecycle.STANDING_ORDER.requireIn(str(so, "status"), "skip the next execution of", "ACTIVE");
        LocalDate due = date(so, "next_execution_date");
        if (due == null) {
            throw ApiException.conflict("no_next_execution", "The standing order has no upcoming execution");
        }
        ObjectNode exec = execution(so, due, 1, "SKIPPED", null, "Skipped at customer request");
        rows.insert("scheduled_payment_execution", exec);
        return advance(so, due, so.path("executions_done").asInt(0));
    }

    private ObjectNode advance(JsonNode so, LocalDate after, int done) {
        Optional<LocalDate> next = Schedule.nextAfter(plan(so), calendar, after, done);
        ObjectNode v = rows.object();
        v.put("executions_done", done);
        if (next.isEmpty()) {
            v.put("status", "COMPLETED");
            v.putNull("next_execution_date");
        } else {
            v.put("next_execution_date", next.get().toString());
        }
        return rows.update(TABLE, uuid(so, "id"), v);
    }

    /** Executes every ACTIVE order due on or before {@code businessDate}. Returns the executions recorded. */
    @Transactional
    public List<ObjectNode> executeDue(LocalDate businessDate) {
        List<ObjectNode> out = new ArrayList<>();
        for (ObjectNode so : rows.query("""
                select row_to_json(s)::text from standing_order s where status = 'ACTIVE'
                  and next_execution_date <= ? order by next_execution_date, id for update""", businessDate)) {
            out.add(execute(so));
        }
        return out;
    }

    private ObjectNode execute(ObjectNode so) {
        UUID id = uuid(so, "id");
        LocalDate due = date(so, "next_execution_date");
        int attempt = so.path("consecutive_failures").asInt(0) + 1;
        ObjectNode acc = accounts.lock(uuid(so, "deposit_account_id"));
        String ccy = str(so, "currency");
        BigDecimal amount = dec(so, "amount");
        ObjectNode exec;
        try {
            accounts.requireDebitAllowed(acc);
            UUID creditTo;
            if (uuid(so, "beneficiary_account_id") != null) {
                ObjectNode b = accounts.get(uuid(so, "beneficiary_account_id"));
                accounts.requireCreditAllowed(b);
                creditTo = accounts.requireLedger(b);
            } else {
                creditTo = props.gl().clearing();
            }
            String ref = str(so, "reference") == null ? "Standing order" : str(so, "reference");
            // the payment reference is what the beneficiary bank / clearing statement shows (RECON-REAL-REFS)
            ledger.post(new LedgerPort.Posting(LedgerPort.key("standing-order", id, due + ":" + attempt), "PAYMENT",
                    ref, ccy, "STANDING_ORDER", id, due, List.of(
                            LedgerPort.Leg.debit(accounts.requireLedger(acc), amount, ref),
                            LedgerPort.Leg.credit(creditTo, amount, ref)))
                    .withRefs(str(so, "reference"), null));
            exec = rows.insert("scheduled_payment_execution", execution(so, due, attempt, "SUCCEEDED", null, null));
            int done = so.path("executions_done").asInt(0) + 1;
            ObjectNode v = rows.object();
            v.put("consecutive_failures", 0);
            rows.update(TABLE, id, v);
            advance(so, due, done);
            rows.execute("update deposit_account set last_customer_activity_at = now() where id = ?", uuid(acc, "id"));
            events.publish("accounts.standing_order.executed", "scheduled_payment_execution", exec);
        } catch (ApiException e) {
            if (e.status() >= 500) {
                throw e;
            }
            String code = "insufficient_funds".equals(e.code()) ? "INSUFFICIENT_FUNDS" : e.code().toUpperCase();
            exec = rows.insert("scheduled_payment_execution", execution(so, due, attempt, "FAILED", code, e.getMessage()));
            int failures = so.path("consecutive_failures").asInt(0) + 1;
            int max = so.path("retry_policy").path("max_attempts").asInt(DEFAULT_MAX_FAILURES);
            ObjectNode v = rows.object();
            v.put("consecutive_failures", failures);
            if (failures >= max) {
                v.put("status", "FAILED_SUSPENDED");
                rows.update(TABLE, id, v);
            } else {
                int spacing = so.path("retry_policy").path("retry_after_days").asInt(1);
                v.put("next_execution_date", calendar.addBusinessDays(due, spacing).toString());
                rows.update(TABLE, id, v);
            }
            events.publish("accounts.standing_order.failed", "scheduled_payment_execution", exec);
        }
        return exec;
    }

    private ObjectNode execution(JsonNode so, LocalDate due, int attempt, String status, String code, String reason) {
        ObjectNode e = rows.object();
        e.put("standing_order_id", str(so, "id"));
        e.put("scheduled_date", due.toString());
        e.put("attempt_no", attempt);
        e.put("status", status);
        if (code != null) {
            e.put("failure_code", code.length() > 64 ? code.substring(0, 64) : code);
        }
        if (reason != null) {
            e.put("failure_reason", reason.length() > 500 ? reason.substring(0, 500) : reason);
        }
        return e;
    }
}
