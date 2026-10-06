package io.meridian.regulatoryreporting.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.regulatoryreporting.domain.FilingCalendar;
import io.meridian.regulatoryreporting.infrastructure.Store;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Report schedules: validated cron / time zone with a computed {@code next_run_at}, and the per-tenant scheduled job
 * that creates and executes the runs due on a business date. A schedule fires on a date when its cron has a fire time
 * on that date in its time zone; the run reports the latest period end on or before that date. An existing
 * (not failed / cancelled) run for the same schedule and reporting date is never duplicated.
 */
@Service
public class ScheduleService {
    private final Store store;
    private final RunService runs;
    private final TransactionTemplate tx;

    public ScheduleService(Store store, RunService runs, TransactionTemplate tx) {
        this.store = store;
        this.runs = runs;
        this.tx = tx;
    }

    /** Validates cron / timezone (when present) and sets next_run_at on the column values. */
    public void prepare(ObjectNode values, JsonNode existing) {
        String cron = values.hasNonNull("cron") ? values.get("cron").asString() : existing == null ? null : Fields.text(existing, "cron");
        String tz = values.hasNonNull("timezone") ? values.get("timezone").asString() : existing == null ? null : Fields.text(existing, "timezone");
        if (values.hasNonNull("report_definition_id")) {
            UUID def = UUID.fromString(values.get("report_definition_id").asString());
            JsonNode d = store.find("report_definition", def).orElseThrow(() -> ApiException.unprocessable(
                    "definition_not_found", "Report definition " + def + " does not exist"));
            if ("DEPRECATED".equals(d.get("status").asString())) {
                throw ApiException.unprocessable("definition_deprecated", "Report definition is deprecated");
            }
        }
        if (cron == null || tz == null) {
            return;
        }
        CronExpression expr = cron(cron);
        ZoneId zone = zone(tz);
        OffsetDateTime now = store.scalar("select now()", OffsetDateTime.class).orElseThrow();
        ZonedDateTime next = expr.next(now.atZoneSameInstant(zone));
        if (next != null) {
            values.put("next_run_at", next.toOffsetDateTime().toString());
        }
    }

    static CronExpression cron(String cron) {
        try {
            return CronExpression.parse(cron);
        } catch (IllegalArgumentException e) {
            throw Fields.invalid("cron", "invalid_cron", "cron is not a valid expression (second minute hour day month weekday): "
                    + e.getMessage());
        }
    }

    static ZoneId zone(String tz) {
        try {
            return ZoneId.of(tz);
        } catch (DateTimeException e) {
            throw Fields.invalid("timezone", "invalid_timezone", "timezone must be an IANA zone id, e.g. Africa/Lagos");
        }
    }

    /** True when the cron has a fire time on {@code date} in the zone. */
    public static boolean firesOn(String cron, String tz, LocalDate date) {
        ZoneId zone = ZoneId.of(tz);
        ZonedDateTime next = CronExpression.parse(cron).next(date.atStartOfDay(zone).minusNanos(1));
        return next != null && next.toLocalDate().equals(date);
    }

    /** Scheduled job body: one run per due schedule (each in its own transactions). */
    public void runDue(LocalDate businessDate, Map<String, Integer> counts, List<String> errors) {
        List<JsonNode> schedules = store.rows("""
                select row_to_json(s)::text from report_schedule s join report_definition d on d.id = s.report_definition_id
                where s.is_active and d.status = 'ACTIVE' order by s.id""");
        int due = 0;
        int created = 0;
        int skipped = 0;
        int failed = 0;
        for (JsonNode s : schedules) {
            UUID scheduleId = RunService.uuid(s, "id");
            try {
                if (!firesOn(s.get("cron").asString(), s.get("timezone").asString(), businessDate)) {
                    continue;
                }
                due++;
                JsonNode def = store.require("report_definition", RunService.uuid(s, "report_definition_id"));
                LocalDate asOf = FilingCalendar.latestPeriodEnd(def.get("frequency").asString(), businessDate);
                boolean exists = store.count("""
                        select count(*) from report_run where report_schedule_id = ? and as_of_date = ?
                          and status not in ('FAILED', 'CANCELLED')""", scheduleId, asOf) > 0;
                if (exists) {
                    skipped++;
                    continue;
                }
                UUID runId = tx.execute(st -> runs.insert(RunService.uuid(s, "report_definition_id"),
                        RunService.uuid(s, "legal_entity_id"), asOf, null, null,
                        s.path("parameters").isObject() ? s.get("parameters") : null, "SCHEDULE", scheduleId, null));
                runs.execute(runId);
                String finalStatus = store.require("report_run", runId).get("status").asString();
                created++;
                OffsetDateTime now = store.scalar("select now()", OffsetDateTime.class).orElseThrow();
                ZonedDateTime next = CronExpression.parse(s.get("cron").asString())
                        .next(now.atZoneSameInstant(ZoneId.of(s.get("timezone").asString())));
                tx.executeWithoutResult(st -> store.update("""
                        update report_schedule set last_run_date = ?, last_run_id = ?, next_run_at = ?, updated_by = ?
                        where id = ?""", businessDate, runId, next == null ? null : next.toOffsetDateTime(),
                        TenantContext.principal(), scheduleId));
                if ("FAILED".equals(finalStatus)) {
                    failed++;
                    errors.add("schedule " + scheduleId + ": run " + runId + " failed");
                }
            } catch (RuntimeException e) {
                failed++;
                errors.add("schedule " + scheduleId + ": " + ScheduledJobs.describe(e));
            }
        }
        counts.put("schedules", schedules.size());
        counts.put("due", due);
        counts.put("runs_created", created);
        counts.put("skipped_existing", skipped);
        counts.put("failed", failed);
    }
}
