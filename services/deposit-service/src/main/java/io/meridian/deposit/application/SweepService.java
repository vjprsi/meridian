package io.meridian.deposit.application;

import static io.meridian.deposit.infrastructure.Rows.bool;
import static io.meridian.deposit.infrastructure.Rows.dec;
import static io.meridian.deposit.infrastructure.Rows.str;
import static io.meridian.deposit.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.deposit.domain.AccountRestrictions;
import io.meridian.deposit.domain.Currencies;
import io.meridian.deposit.domain.Schedule;
import io.meridian.deposit.domain.SweepCalculator;
import io.meridian.deposit.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Sweep rules between two deposit accounts of the same currency, evaluated on demand or on schedule. */
@Service
public class SweepService {
    static final String TABLE = "sweep_rule";

    private final Rows rows;
    private final AccountSupport accounts;
    private final LedgerPort ledger;

    public SweepService(Rows rows, AccountSupport accounts, LedgerPort ledger) {
        this.rows = rows;
        this.accounts = accounts;
        this.ledger = ledger;
    }

    @Transactional
    public ObjectNode create(Object body) {
        ObjectNode v = rows.columns(TABLE, body);
        validate(v);
        v.put("next_run_at", nextRun(v, accounts.now()).toString());
        return rows.insert(TABLE, v);
    }

    private void validate(JsonNode v) {
        UUID source = uuid(v, "source_account_id");
        UUID target = uuid(v, "target_account_id");
        if (source.equals(target)) {
            throw ApiException.unprocessable("same_account", "source and target accounts must differ");
        }
        JsonNode s = accounts.reference(source, "source_account_id");
        JsonNode t = accounts.reference(target, "target_account_id");
        accounts.requireCurrency(t, str(s, "currency"));
        String type = str(v, "sweep_type");
        boolean needsThreshold = "THRESHOLD_OUT".equals(type) || "THRESHOLD_IN".equals(type);
        if (needsThreshold && dec(v, "threshold_amount") == null) {
            throw ApiException.unprocessable("threshold_required", type + " sweeps need threshold_amount");
        }
        if ("TARGET_BALANCE".equals(type) && dec(v, "target_balance") == null) {
            throw ApiException.unprocessable("target_balance_required", "TARGET_BALANCE sweeps need target_balance");
        }
        for (String f : List.of("threshold_amount", "target_balance", "min_sweep_amount")) {
            BigDecimal d = dec(v, f);
            if (d != null && d.signum() < 0) {
                throw ApiException.unprocessable("invalid_amount", f + " must not be negative");
            }
        }
        String time = str(v, "run_time");
        if (time != null && !time.matches("([01]\\d|2[0-3]):[0-5]\\d")) {
            throw ApiException.unprocessable("invalid_run_time", "run_time must be HH:MM (24h)");
        }
    }

    static OffsetDateTime nextRun(JsonNode rule, OffsetDateTime now) {
        String time = str(rule, "run_time");
        LocalTime at = time == null ? LocalTime.of(23, 0) : LocalTime.parse(time);
        OffsetDateTime candidate = now.toLocalDate().atTime(at).atOffset(ZoneOffset.UTC);
        if (!candidate.isAfter(now)) {
            String f = str(rule, "frequency");
            candidate = Schedule.advance(candidate.toLocalDate(), Schedule.frequency(f == null ? "DAILY" : f))
                    .atTime(at).atOffset(ZoneOffset.UTC);
        }
        return candidate;
    }

    /**
     * Evaluates a sweep now and records the execution (SUCCEEDED, SKIPPED or FAILED). A ledger rejection (e.g.
     * insufficient funds, restricted account) is recorded as FAILED rather than surfaced as an error.
     */
    @Transactional
    public ObjectNode run(UUID ruleId, String clientKey) {
        ObjectNode rule = rows.lock(TABLE, ruleId);
        if (!bool(rule, "is_active")) {
            throw ApiException.conflict("rule_inactive", "Sweep rule " + ruleId + " is not active");
        }
        ObjectNode source = accounts.lock(uuid(rule, "source_account_id"));
        ObjectNode target = accounts.lock(uuid(rule, "target_account_id"));
        String ccy = str(source, "currency");
        UUID executionId = Ids.newId();
        ObjectNode exec = rows.object();
        exec.put("id", executionId.toString());
        exec.put("sweep_rule_id", ruleId.toString());
        exec.put("executed_at", accounts.now().toString());
        exec.put("currency", ccy);
        String problem = restriction(source, target);
        if (problem != null) {
            exec.put("status", "FAILED");
            exec.put("failure_reason", problem);
            return finish(rule, exec);
        }
        UUID sourceLedger = accounts.requireLedger(source);
        UUID targetLedger = accounts.requireLedger(target);
        BigDecimal sBal = LedgerPort.posted(ledger.balance(sourceLedger));
        BigDecimal tBal = LedgerPort.posted(ledger.balance(targetLedger));
        SweepCalculator.Decision d;
        try {
            d = SweepCalculator.decide(str(rule, "sweep_type"), sBal, tBal, dec(rule, "threshold_amount"),
                    dec(rule, "target_balance"), dec(rule, "min_sweep_amount"));
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("invalid_rule", e.getMessage());
        }
        if (d.direction() == SweepCalculator.Direction.NONE) {
            exec.put("status", "SKIPPED");
            exec.put("amount", "0");
            exec.put("failure_reason", d.reason());
            return finish(rule, exec);
        }
        BigDecimal amount = Currencies.round(d.amount(), ccy);
        boolean out = d.direction() == SweepCalculator.Direction.TO_TARGET;
        UUID from = out ? sourceLedger : targetLedger;
        UUID to = out ? targetLedger : sourceLedger;
        try {
            UUID je = ledger.post(new LedgerPort.Posting(LedgerPort.key("sweep", ruleId, clientKey), "TRANSFER",
                    "Sweep " + str(rule, "sweep_type") + ": " + d.reason(), ccy, "SWEEP_RULE", ruleId, null, List.of(
                            LedgerPort.Leg.debit(from, amount, "Sweep"), LedgerPort.Leg.credit(to, amount, "Sweep"))));
            exec.put("status", "SUCCEEDED");
            exec.put("amount", amount.toPlainString());
            exec.put("journal_entry_id", je.toString());
        } catch (ApiException e) {
            if (e.status() >= 500) {
                throw e;
            }
            exec.put("status", "FAILED");
            exec.put("amount", amount.toPlainString());
            exec.put("failure_reason", e.code() + ": " + e.getMessage());
        }
        return finish(rule, exec);
    }

    private static String restriction(JsonNode source, JsonNode target) {
        if (!AccountRestrictions.allowsDebit(str(source, "status")) && !AccountRestrictions.allowsDebit(str(target, "status"))) {
            return "neither account allows debits";
        }
        if ("CLOSED".equals(str(source, "status")) || "CLOSED".equals(str(target, "status"))) {
            return "an account of the rule is closed";
        }
        return null;
    }

    private ObjectNode finish(ObjectNode rule, ObjectNode exec) {
        ObjectNode saved = rows.insert("sweep_execution", exec);
        ObjectNode v = rows.object();
        v.put("next_run_at", nextRun(rule, accounts.now()).toString());
        rows.update(TABLE, uuid(rule, "id"), v);
        return saved;
    }

    /** Runs every active rule whose next_run_at has passed (scheduled job). */
    @Transactional
    public int runDue(OffsetDateTime now) {
        int n = 0;
        for (ObjectNode rule : rows.query("""
                select row_to_json(r)::text from sweep_rule r where is_active and next_run_at <= ? order by next_run_at""", now)) {
            run(uuid(rule, "id"), str(rule, "next_run_at"));
            n++;
        }
        return n;
    }
}
