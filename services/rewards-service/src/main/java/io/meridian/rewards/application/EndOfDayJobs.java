package io.meridian.rewards.application;

import static io.meridian.rewards.application.Json.text;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.rewards.domain.PointsLots;
import io.meridian.rewards.infrastructure.Rows;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * End-of-day jobs of the current tenant for one business date, called by business-day-service
 * ({@code POST /v1/rewards/eod-runs/...?business_date=}). Each execution is recorded in {@code rewards_eod_run}: a
 * COMPLETED job is never executed again for that date (its stored result is replayed with {@code replayed=true});
 * COMPLETED_WITH_ERRORS may be re-run (every item is idempotent through deterministic ledger keys and status guards);
 * a RUNNING job is refused with 409 unless stale. Each item runs in its own transaction so one failure does not undo
 * the others.
 */
@Component
public class EndOfDayJobs {
    private static final Logger log = LoggerFactory.getLogger(EndOfDayJobs.class);
    private static final int STALE_HOURS = 2;
    private static final int MAX_ERRORS = 50;

    public enum Job { POINTS_CONFIRMATION, POINTS_EXPIRY, CASHBACK_PAYOUT, TIER_REQUALIFICATION, REFERRAL_EXPIRY }

    private final Rows rows;
    private final PointsLedger ledger;
    private final CashbackService cashback;
    private final AccountService accounts;
    private final ReferralService referrals;
    private final TransactionTemplate tx;

    public EndOfDayJobs(Rows rows, PointsLedger ledger, CashbackService cashback, AccountService accounts,
                        ReferralService referrals, TransactionTemplate tx) {
        this.rows = rows;
        this.ledger = ledger;
        this.cashback = cashback;
        this.accounts = accounts;
        this.referrals = referrals;
        this.tx = tx;
    }

    public ObjectNode run(Job job, LocalDate businessDate) {
        if (businessDate == null) {
            throw ApiException.badRequest("business_date_required", "business_date is required");
        }
        Optional<ObjectNode> claimed = tx.execute(s -> claim(job, businessDate));
        if (claimed == null || claimed.isEmpty()) {
            ObjectNode existing = rows.queryOne("select row_to_json(r)::text from rewards_eod_run r where job = ? and business_date = ?",
                    job.name(), businessDate).orElseThrow();
            if ("RUNNING".equals(text(existing, "status"))) {
                throw ApiException.conflict("eod_job_running", job + " for " + businessDate + " is already running");
            }
            return response(existing, true);
        }
        UUID runId = Json.uuid(claimed.get(), "id");
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        try {
            execute(job, businessDate, counts, errors);
        } catch (RuntimeException e) {
            log.error("end-of-day job {} for {} failed", job, businessDate, e);
            errors.add(describe(e));
        }
        ObjectNode result = rows.object();
        ObjectNode c = result.putObject("counts");
        counts.forEach(c::put);
        ArrayNode errs = result.putArray("errors");
        errors.stream().limit(MAX_ERRORS).forEach(errs::add);
        result.put("error_count", errors.size());
        ObjectNode done = tx.execute(s -> rows.queryOne("""
                update rewards_eod_run set status = ?, result = ?::jsonb, completed_at = now() where id = ?
                returning row_to_json(rewards_eod_run)::text""",
                errors.isEmpty() ? "COMPLETED" : "COMPLETED_WITH_ERRORS", result.toString(), runId).orElseThrow());
        return response(done, false);
    }

    private void execute(Job job, LocalDate date, Map<String, Integer> counts, List<String> errors) {
        OffsetDateTime endOfDay = date.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        switch (job) {
            case POINTS_CONFIRMATION -> {
                int points = 0;
                for (UUID id : ids("""
                        select e.id from points_ledger_entry e where e.is_pending and e.entry_type = 'EARN' and e.points > 0
                          and e.confirm_after < ?
                          and not exists (select 1 from points_ledger_entry c where c.confirms_id = e.id)
                          and not exists (select 1 from points_ledger_entry r where r.reversal_of_id = e.id)
                        order by e.id""", endOfDay)) {
                    points += guarded("confirm points " + id, errors, () -> inTx(() -> confirmPoints(id)));
                }
                counts.put("points_entries_confirmed", points);
                int cash = 0;
                for (UUID id : ids("select id from cashback_transaction where status = 'PENDING' and confirm_after < ? order by id", endOfDay)) {
                    cash += guarded("confirm cashback " + id, errors, () -> inTx(() -> {
                        ObjectNode c = rows.lock(CashbackService.T, id);
                        if (!"PENDING".equals(text(c, "status"))) {
                            return 0;
                        }
                        cashback.confirmLocked(c);
                        return 1;
                    }));
                }
                counts.put("cashback_confirmed", cash);
            }
            case POINTS_EXPIRY -> {
                int accountsTouched = 0;
                int lots = 0;
                long points = 0;
                for (UUID id : ids("""
                        select distinct points_account_id from points_ledger_entry
                        where expires_on is not null and expires_on <= ? order by 1""", date)) {
                    long[] r = new long[2];
                    guarded("expire " + id, errors, () -> inTx(() -> {
                        ObjectNode account = ledger.lockAccount(id);
                        for (PointsLots.Lot lot : ledger.lots(id).expiredBy(date)) {
                            ledger.append(account, new PointsLedger.Move("EXPIRE", -lot.remaining(), "expire:" + lot.id())
                                    .lot(lot.id()).source("EXPIRY", lot.id()).reason("Points earned " + lot.earnDate()
                                            + " expired on " + lot.expiresOn()).event("engagement.points.expired"));
                            r[0]++;
                            r[1] += lot.remaining();
                        }
                        ledger.refreshExpiry(rows.get("points_account", id));
                        return 1;
                    }));
                    if (r[0] > 0) {
                        accountsTouched++;
                    }
                    lots += (int) r[0];
                    points += r[1];
                }
                counts.put("accounts", accountsTouched);
                counts.put("lots_expired", lots);
                counts.put("points_expired", (int) points);
            }
            case CASHBACK_PAYOUT -> {
                int paid = 0;
                int failed = 0;
                for (UUID id : ids("select id from cashback_transaction where status = 'CONFIRMED' and confirm_after < ? order by id", endOfDay)) {
                    int[] r = new int[1];
                    guarded("pay cashback " + id, errors, () -> inTx(() -> {
                        ObjectNode c = rows.lock(CashbackService.T, id);
                        if (!"CONFIRMED".equals(text(c, "status"))) {
                            return 0;
                        }
                        ObjectNode out = cashback.payLocked(c);
                        r[0] = "PAID".equals(text(out, "status")) ? 1 : -1;
                        if (r[0] < 0) {
                            errors.add("pay cashback " + id + ": " + text(out, "failure_reason"));
                        }
                        return 1;
                    }));
                    if (r[0] > 0) {
                        paid++;
                    } else if (r[0] < 0) {
                        failed++;
                    }
                }
                counts.put("cashback_paid", paid);
                counts.put("cashback_failed", failed);
            }
            case TIER_REQUALIFICATION -> {
                int changed = 0;
                int evaluated = 0;
                for (UUID id : ids("""
                        select id from points_account where status <> 'CLOSED'
                          and (tier_qualified_until is null or tier_qualified_until <= ?) order by id""", date)) {
                    evaluated++;
                    changed += guarded("tier " + id, errors, () -> inTx(() -> {
                        ObjectNode a = rows.lock("points_account", id);
                        String before = text(a, "reward_tier_id");
                        ObjectNode program = rows.get("rewards_program", Json.uuid(a, "rewards_program_id"));
                        ObjectNode after = accounts.evaluateTier(a, program, date, false);
                        return java.util.Objects.equals(before, text(after, "reward_tier_id")) ? 0 : 1;
                    }));
                }
                counts.put("accounts_evaluated", evaluated);
                counts.put("tiers_changed", changed);
            }
            case REFERRAL_EXPIRY -> counts.put("referrals_expired",
                    guarded("referral expiry", errors, () -> inTx(() -> referrals.expire(date))));
        }
    }

    /** Confirms one pending earn: a new non-pending EARN entry that points at it (exactly once by key). */
    private int confirmPoints(UUID pendingId) {
        ObjectNode pending = rows.get("points_ledger_entry", pendingId);
        UUID accountId = Json.uuid(pending, "points_account_id");
        ObjectNode account = ledger.lockAccount(accountId);
        if ("CLOSED".equals(text(account, "status"))) {
            return 0;
        }
        PointsLedger.Move m = new PointsLedger.Move("EARN", pending.path("points").asLong(), "confirm:" + pendingId)
                .confirms(pendingId).rule(Json.uuid(pending, "reward_rule_id"))
                .source(text(pending, "source_type"), Json.uuid(pending, "source_id"))
                .expires(Json.date(pending, "expires_on")).event("engagement.points.confirmed");
        if (text(pending, "reward_transaction_id") != null) {
            m.transaction(Json.uuid(pending, "reward_transaction_id"), Json.decimal(pending, "transaction_amount"), text(pending, "currency"));
        }
        ledger.append(account, m);
        return 1;
    }

    private int inTx(IntSupplier body) {
        Integer r = tx.execute(s -> body.getAsInt());
        return r == null ? 0 : r;
    }

    private List<UUID> ids(String sql, Object... args) {
        return rows.jdbc().sql(sql).params(java.util.Arrays.asList(args)).query(UUID.class).list();
    }

    private Optional<ObjectNode> claim(Job job, LocalDate date) {
        String principal = TenantContext.principal();
        return rows.queryOne("""
                insert into rewards_eod_run as r (id, tenant_id, job, business_date, status, attempts, started_at,
                                                  created_by, updated_by)
                values (?, ?, ?, ?, 'RUNNING', 1, now(), ?, ?)
                on conflict (tenant_id, job, business_date) do update
                  set status = 'RUNNING', attempts = r.attempts + 1, started_at = now(), completed_at = null,
                      result = null, updated_at = now(), updated_by = excluded.updated_by
                  where r.status = 'COMPLETED_WITH_ERRORS'
                     or (r.status = 'RUNNING' and r.started_at < now() - make_interval(hours => ?))
                returning row_to_json(r)::text""",
                Ids.newId(), TenantContext.require(), job.name(), date, principal, principal, STALE_HOURS);
    }

    private ObjectNode response(ObjectNode run, boolean replayed) {
        ObjectNode out = rows.object();
        out.put("run_id", text(run, "id"));
        out.put("job", text(run, "job"));
        out.put("business_date", text(run, "business_date"));
        out.put("status", text(run, "status"));
        out.put("attempts", run.path("attempts").asInt(1));
        out.put("replayed", replayed);
        out.put("started_at", text(run, "started_at"));
        out.put("completed_at", text(run, "completed_at"));
        if (run.path("result").isObject()) {
            out.setAll((ObjectNode) run.get("result"));
        }
        return out;
    }

    private static int guarded(String what, List<String> errors, IntSupplier job) {
        try {
            return job.getAsInt();
        } catch (RuntimeException e) {
            log.error("end-of-day step '{}' failed", what, e);
            errors.add(what + ": " + describe(e));
            return 0;
        }
    }

    private static String describe(RuntimeException e) {
        return e instanceof ApiException a ? a.code() + " - " + a.getMessage() : e.getClass().getSimpleName() + " - " + e.getMessage();
    }
}
