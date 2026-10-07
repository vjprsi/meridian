package io.meridian.collections.application;

import io.meridian.collections.config.CollectionsProperties;
import io.meridian.collections.domain.ArrangementTracker;
import io.meridian.collections.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The collections end-of-day strategy run of the current tenant for one business date, called by
 * business-day-service ({@code POST /v1/collections/cases/eod/strategy-run}). Recorded in collections_eod_run: a
 * COMPLETED run is replayed ({@code replayed=true}), COMPLETED_WITH_ERRORS may be re-run (every step is
 * idempotent), a RUNNING run is refused with 409 unless stale. Each case is processed in its own transaction so one
 * failing exposure does not stop the run; errors are reported per item.
 */
@Component
public class CollectionsEod {
    private static final Logger log = LoggerFactory.getLogger(CollectionsEod.class);
    static final String JOB = "COLLECTIONS_STRATEGY_RUN";
    private static final int STALE_HOURS = 2;
    private static final int MAX_ERRORS = 50;

    private final Rows rows;
    private final ExposurePort exposures;
    private final CaseService cases;
    private final ArrangementService arrangements;
    private final StrategyRunner runner;
    private final QueueService queues;
    private final CollectionsProperties props;

    public CollectionsEod(Rows rows, ExposurePort exposures, CaseService cases, ArrangementService arrangements,
                          StrategyRunner runner, QueueService queues, CollectionsProperties props) {
        this.rows = rows;
        this.exposures = exposures;
        this.cases = cases;
        this.arrangements = arrangements;
        this.runner = runner;
        this.queues = queues;
        this.props = props;
    }

    /** Fallback when business-day-service does not orchestrate: runs yesterday for the configured tenants. */
    @Scheduled(cron = "${meridian.collections.batch.cron:0 0 2 * * *}", zone = "UTC")
    public void scheduled() {
        if (!props.batch().enabled()) {
            return;
        }
        for (UUID tenant : props.batch().tenants()) {
            try {
                TenantContext.callAs(tenant, "system:end-of-day", () -> run(rows.today().minusDays(1)));
            } catch (Exception e) {
                log.error("collections strategy run failed for tenant {}", tenant, e);
            }
        }
    }

    public ObjectNode run(LocalDate businessDate) {
        if (businessDate == null) {
            throw ApiException.badRequest("business_date_required", "business_date is required");
        }
        Optional<ObjectNode> claimed = claim(businessDate);
        if (claimed.isEmpty()) {
            ObjectNode existing = rows.queryOne("""
                    select row_to_json(r)::text from collections_eod_run r where job = ? and business_date = ?""",
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
            execute(businessDate, counts, errors);
        } catch (RuntimeException e) {
            log.error("collections strategy run for {} failed", businessDate, e);
            errors.add(describe(e));
        }
        ObjectNode result = rows.object();
        ObjectNode c = result.putObject("counts");
        counts.forEach(c::put);
        ArrayNode errs = result.putArray("errors");
        errors.stream().limit(MAX_ERRORS).forEach(errs::add);
        result.put("error_count", errors.size());
        ObjectNode done = rows.queryOne("""
                update collections_eod_run set status = ?, result = ?::jsonb, completed_at = now() where id = ?
                returning row_to_json(collections_eod_run)::text""",
                errors.isEmpty() ? "COMPLETED" : "COMPLETED_WITH_ERRORS", result.toString(), runId).orElseThrow();
        return response(done, false);
    }

    private void execute(LocalDate date, Map<String, Integer> counts, List<String> errors) {
        // 1. delinquent exposures -> open / refresh cases
        Set<String> seen = new HashSet<>();
        int opened = 0;
        int refreshed = 0;
        List<ExposurePort.Exposure> delinquent = List.of();
        try {
            delinquent = exposures.delinquent();
        } catch (RuntimeException e) {
            errors.add("sync delinquent exposures: " + describe(e));
        }
        for (ExposurePort.Exposure x : delinquent) {
            seen.add(x.exposureType() + ":" + x.id());
            boolean existed = rows.scalar("""
                    select count(*) from collection_case where exposure_type = ? and exposure_id = ?
                      and status not in ('RESOLVED', 'CLOSED')""", Integer.class, x.exposureType(), x.id()).orElse(0) > 0;
            if (guarded("open " + x.exposureType() + " " + x.id(), errors, () -> cases.openOrRefresh(signal(x)))) {
                if (existed) {
                    refreshed++;
                } else {
                    opened++;
                }
            }
        }
        counts.put("delinquent_exposures", delinquent.size());
        counts.put("cases_opened", opened);
        counts.put("cases_refreshed", refreshed);

        // 2. per open case: payments (last payment, promises, plans), then cure / pay-off / write-off / refresh of
        //    exposures no longer reported in arrears. Arrangements are tracked first so a promise kept by the payment
        //    that cured the exposure is recorded as KEPT before the case resolves.
        int cured = 0;
        int writtenOff = 0;
        Map<String, Integer> outcomes = new LinkedHashMap<>();
        for (ObjectNode c : openCases()) {
            String type = Rows.str(c, "exposure_type");
            UUID exposureId = Rows.uuid(c, "exposure_id");
            UUID caseId = Rows.uuid(c, "id");
            if (!ExposurePort.servedByLoanService(type)) {
                continue;
            }
            try {
                List<ArrangementTracker.Payment> pay = exposures.payments(type, exposureId);
                guarded("last payment " + caseId, errors, () -> lastPayment(caseId, pay));
                for (ObjectNode p : rows.query("""
                        select row_to_json(p)::text from promise_to_pay p where collection_case_id = ? and status = 'ACTIVE'""",
                        caseId)) {
                    String[] out = new String[1];
                    guarded("promise " + Rows.str(p, "id"), errors,
                            () -> out[0] = arrangements.trackPromise(Rows.uuid(p, "id"), pay, date));
                    if (out[0] != null) {
                        outcomes.merge("promises_" + out[0].toLowerCase(java.util.Locale.ROOT), 1, Integer::sum);
                    }
                }
                for (ObjectNode p : rows.query("""
                        select row_to_json(p)::text from payment_plan p where collection_case_id = ? and status = 'ACTIVE'""",
                        caseId)) {
                    String[] out = new String[1];
                    guarded("plan " + Rows.str(p, "id"), errors,
                            () -> out[0] = arrangements.trackPlan(Rows.uuid(p, "id"), pay, date));
                    if (out[0] != null) {
                        outcomes.merge("plans_" + out[0].toLowerCase(java.util.Locale.ROOT), 1, Integer::sum);
                    }
                }
            } catch (RuntimeException ex) {
                errors.add("payments " + exposureId + ": " + describe(ex));
            }
            if (seen.contains(type + ":" + exposureId)) {
                continue;
            }
            Optional<ExposurePort.Exposure> x;
            try {
                x = exposures.get(type, exposureId);
            } catch (RuntimeException e) {
                errors.add("exposure " + exposureId + ": " + describe(e));
                continue;
            }
            if (x.isEmpty()) {
                continue;
            }
            ExposurePort.Exposure e = x.get();
            if (e.paidOff()) {
                cured += guarded("pay-off " + caseId, errors,
                        () -> cases.resolve(caseId, "PAID_OFF", "Exposure paid off")) ? 1 : 0;
            } else if (e.writtenOff()) {
                writtenOff += guarded("write-off " + caseId, errors, () -> cases.markWrittenOff(caseId)) ? 1 : 0;
            } else if (e.daysPastDue() <= 0) {
                cured += guarded("cure " + caseId, errors,
                        () -> cases.resolve(caseId, "CURED", "Exposure is current again")) ? 1 : 0;
            } else {
                guarded("refresh " + caseId, errors, () -> cases.openOrRefresh(signal(e)));
            }
        }
        counts.put("cases_cured", cured);
        counts.put("cases_written_off", writtenOff);
        counts.putAll(outcomes);

        // 4. strategy steps (and priority)
        int sent = 0;
        int suppressed = 0;
        int routed = 0;
        for (ObjectNode c : openCases()) {
            UUID caseId = Rows.uuid(c, "id");
            StrategyRunner.StepResult[] r = new StrategyRunner.StepResult[1];
            if (guarded("strategy " + caseId, errors, () -> r[0] = runner.run(caseId, date))) {
                sent += r[0].sent();
                suppressed += r[0].suppressed();
                routed += r[0].routed();
            }
        }
        counts.put("actions_sent", sent);
        counts.put("actions_suppressed", suppressed);
        counts.put("cases_routed", routed);

        // 5. distribute unassigned cases of each queue
        int assigned = 0;
        for (ObjectNode q : rows.query("select row_to_json(q)::text from collection_queue q where is_active order by code")) {
            int[] n = new int[1];
            guarded("queue " + Rows.str(q, "code"), errors, () -> n[0] = queues.distribute(Rows.uuid(q, "id")));
            assigned += n[0];
        }
        counts.put("cases_assigned", assigned);
    }

    void lastPayment(UUID caseId, List<ArrangementTracker.Payment> payments) {
        LocalDate last = null;
        for (ArrangementTracker.Payment p : payments) {
            if ("POSTED".equals(p.status()) && p.reversalOfId() == null && p.valueDate() != null
                    && (last == null || p.valueDate().isAfter(last))) {
                last = p.valueDate();
            }
        }
        if (last != null) {
            rows.execute("""
                    update collection_case set last_payment_at = ?::date::timestamptz where id = ?
                      and (last_payment_at is null or last_payment_at < ?::date::timestamptz)""",
                    last.toString(), caseId, last.toString());
        }
    }

    private List<ObjectNode> openCases() {
        return rows.query("""
                select row_to_json(c)::text from collection_case c where status not in ('RESOLVED', 'CLOSED') order by id""");
    }

    private static CaseService.Signal signal(ExposurePort.Exposure x) {
        return new CaseService.Signal(x.customerId(), x.exposureType(), x.id(), x.daysPastDue(), x.overdueAmount(),
                x.totalOutstanding(), x.currency(), x.productId(), x.riskGrade());
    }

    private Optional<ObjectNode> claim(LocalDate date) {
        String principal = TenantContext.principal();
        return rows.queryOne("""
                insert into collections_eod_run as r (id, tenant_id, job, business_date, status, attempts, started_at,
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

    private static boolean guarded(String what, List<String> errors, Runnable job) {
        try {
            job.run();
            return true;
        } catch (RuntimeException e) {
            log.warn("collections step '{}' failed: {}", what, e.getMessage());
            errors.add(what + ": " + describe(e));
            return false;
        }
    }

    private static String describe(RuntimeException e) {
        return e instanceof ApiException a ? a.code() + " - " + a.getMessage() : e.getClass().getSimpleName() + " - " + e.getMessage();
    }
}
