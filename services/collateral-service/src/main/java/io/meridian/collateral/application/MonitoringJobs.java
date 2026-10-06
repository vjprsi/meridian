package io.meridian.collateral.application;

import io.meridian.collateral.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * End-of-day collateral monitoring of the current tenant for one business date, called by business-day-service
 * ({@code POST /v1/collaterals/eod/monitor?business_date=}): LTV recalculation of every secured exposure (alerts
 * when a link worsens to WARNING or BREACH), insurance expiring within {@link #INSURANCE_NOTICE_DAYS} days (warned
 * once per policy end date) and revaluations due (on the due date, then weekly while overdue).
 *
 * <p>Each execution is recorded in {@code collateral_eod_run}: a COMPLETED date is replayed with {@code replayed=true},
 * COMPLETED_WITH_ERRORS may be re-run, a RUNNING one is refused with 409 unless stale.
 */
@Component
public class MonitoringJobs {
    private static final Logger log = LoggerFactory.getLogger(MonitoringJobs.class);
    static final String JOB = "COLLATERAL_MONITORING";
    static final int INSURANCE_NOTICE_DAYS = 30;
    private static final int STALE_HOURS = 2;
    private static final int MAX_ERRORS = 50;

    private final Rows rows;
    private final LinkService links;
    private final Events events;
    private final TransactionTemplate tx;

    public MonitoringJobs(Rows rows, LinkService links, Events events, TransactionTemplate tx) {
        this.rows = rows;
        this.links = links;
        this.events = events;
        this.tx = tx;
    }

    public ObjectNode run(LocalDate businessDate) {
        if (businessDate == null) {
            throw ApiException.badRequest("business_date_required", "business_date is required");
        }
        Optional<ObjectNode> claimed = claim(businessDate);
        if (claimed.isEmpty()) {
            ObjectNode existing = rows.queryOne("""
                    select row_to_json(r)::text from collateral_eod_run r where job = ? and business_date = ?""",
                    JOB, businessDate).orElseThrow();
            if ("RUNNING".equals(Rows.str(existing, "status"))) {
                throw ApiException.conflict("eod_job_running", JOB + " for " + businessDate + " is already running");
            }
            return response(existing, true);
        }
        UUID runId = Rows.uuid(claimed.get(), "id");
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        try {
            ltv(counts, errors);
            insurance(businessDate, counts, errors);
            revaluations(businessDate, counts, errors);
        } catch (RuntimeException e) {
            log.error("collateral monitoring for {} failed", businessDate, e);
            errors.add(describe(e));
        }
        ObjectNode result = rows.object();
        ObjectNode c = result.putObject("counts");
        counts.forEach(c::put);
        ArrayNode errs = result.putArray("errors");
        errors.stream().limit(MAX_ERRORS).forEach(errs::add);
        result.put("error_count", errors.size());
        ObjectNode done = rows.queryOne("""
                update collateral_eod_run set status = ?, result = ?::jsonb, completed_at = now() where id = ?
                returning row_to_json(collateral_eod_run)::text""",
                errors.isEmpty() ? "COMPLETED" : "COMPLETED_WITH_ERRORS", result.toString(), runId).orElseThrow();
        return response(done, false);
    }

    private void ltv(Map<String, Integer> counts, List<String> errors) {
        List<ObjectNode> exposures = rows.query("""
                select json_build_object('exposure_type', exposure_type, 'exposure_id', exposure_id)::text
                from collateral_link where is_active group by exposure_type, exposure_id order by exposure_id""");
        int linksDone = 0;
        int warnings = 0;
        int breaches = 0;
        for (ObjectNode e : exposures) {
            String type = Rows.str(e, "exposure_type");
            UUID id = Rows.uuid(e, "exposure_id");
            try {
                List<ObjectNode> result = links.recalculate(type, id);
                linksDone += result.size();
                for (ObjectNode l : result) {
                    String s = Rows.str(l, "ltv_status");
                    if ("WARNING".equals(s)) {
                        warnings++;
                    } else if ("BREACH".equals(s)) {
                        breaches++;
                    }
                }
            } catch (RuntimeException ex) {
                errors.add("ltv " + type + " " + id + ": " + describe(ex));
            }
        }
        counts.put("exposures", exposures.size());
        counts.put("links_recalculated", linksDone);
        counts.put("ltv_warnings", warnings);
        counts.put("ltv_breaches", breaches);
    }

    private void insurance(LocalDate date, Map<String, Integer> counts, List<String> errors) {
        int[] notified = {0};
        tx.executeWithoutResult(s -> {
            List<ObjectNode> expiring = rows.query("""
                    update collateral_insurance i set expiry_notified_at = now(), updated_by = ?
                    from collateral c
                    where c.id = i.collateral_id and c.status in ('PROPOSED', 'VERIFIED', 'PLEDGED')
                      and i.expiry_notified_at is null and i.end_date >= ? and i.end_date <= ?
                    returning row_to_json(i)::text""",
                    TenantContext.principal(), date, date.plusDays(INSURANCE_NOTICE_DAYS));
            for (ObjectNode i : expiring) {
                events.publish("lending.collateral_insurance.expiring", "collateral_insurance", i);
                notified[0]++;
            }
        });
        counts.put("insurance_expiring", notified[0]);
        counts.put("insurance_lapsed", rows.scalar("""
                select count(*) from collateral_insurance i join collateral c on c.id = i.collateral_id
                where c.status in ('VERIFIED', 'PLEDGED') and i.end_date < ?
                  and not exists (select 1 from collateral_insurance j where j.collateral_id = i.collateral_id
                                  and j.end_date >= ?)""", Integer.class, date, date).orElse(0));
    }

    private void revaluations(LocalDate date, Map<String, Integer> counts, List<String> errors) {
        int[] due = {0};
        tx.executeWithoutResult(s -> {
            for (ObjectNode c : rows.query("""
                    select row_to_json(c)::text from collateral c
                    where status in ('VERIFIED', 'PLEDGED') and next_valuation_date <= ? order by id""", date)) {
                long overdueDays = ChronoUnit.DAYS.between(Rows.date(c, "next_valuation_date"), date);
                if (overdueDays % 7 == 0) {
                    events.publish("lending.collateral.revaluation_due", "collateral", c);
                    due[0]++;
                }
            }
        });
        counts.put("revaluations_due", due[0]);
    }

    private Optional<ObjectNode> claim(LocalDate date) {
        String principal = TenantContext.principal();
        return rows.queryOne("""
                insert into collateral_eod_run as r (id, tenant_id, job, business_date, status, attempts, started_at,
                                                     created_by, updated_by)
                values (?, ?, ?, ?, 'RUNNING', 1, now(), ?, ?)
                on conflict (tenant_id, job, business_date) do update
                  set status = 'RUNNING', attempts = r.attempts + 1, started_at = now(), completed_at = null,
                      result = null, updated_at = now(), updated_by = excluded.updated_by
                  where r.status = 'COMPLETED_WITH_ERRORS'
                     or (r.status = 'RUNNING' and r.started_at < now() - make_interval(hours => ?))
                returning row_to_json(r)::text""",
                Ids.newId(), TenantContext.require(), JOB, date, principal, principal, STALE_HOURS);
    }

    private ObjectNode response(ObjectNode run, boolean replayed) {
        ObjectNode out = rows.object();
        out.put("run_id", Rows.str(run, "id"));
        out.put("job", Rows.str(run, "job"));
        out.put("business_date", Rows.str(run, "business_date"));
        out.put("status", Rows.str(run, "status"));
        out.put("attempts", run.path("attempts").asInt(1));
        out.put("replayed", replayed);
        out.put("started_at", Rows.str(run, "started_at"));
        out.put("completed_at", Rows.str(run, "completed_at"));
        if (run.path("result").isObject()) {
            out.setAll((ObjectNode) run.get("result"));
        }
        return out;
    }

    private static String describe(RuntimeException e) {
        return e instanceof ApiException a ? a.code() + " - " + a.getMessage() : e.getClass().getSimpleName() + " - " + e.getMessage();
    }
}
