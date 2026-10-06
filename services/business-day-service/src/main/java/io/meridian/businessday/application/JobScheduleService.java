package io.meridian.businessday.application;

import io.meridian.businessday.domain.BusinessCalendar;
import io.meridian.businessday.domain.DomainException;
import io.meridian.businessday.infrastructure.CalendarProvider;
import io.meridian.businessday.infrastructure.JobDispatcher;
import io.meridian.businessday.infrastructure.JsonValues;
import io.meridian.businessday.infrastructure.LockStore;
import io.meridian.businessday.infrastructure.Rows;
import io.meridian.businessday.infrastructure.ServiceCatalog;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.web.crud.CrudEngine;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;
import java.util.UUID;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Cron schedules for non-EOD batch jobs: validation, next-run computation (Spring {@link CronExpression} in the
 * schedule's time zone, optionally business days only), pause / resume, and manual runs guarded by a lease
 * lock so the same job never runs twice concurrently.
 */
@Service
public class JobScheduleService {
    private static final Set<String> MISFIRE = Set.of("RUN_ONCE", "SKIP", "RUN_ALL");
    private static final int MAX_CRON_SCAN = 1000;

    private final Rows rows;
    private final CrudEngine crud;
    private final CalendarProvider calendars;
    private final JobDispatcher dispatcher;
    private final LockStore locks;
    private final ServiceCatalog catalog;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final EodSettings settings;
    private final JsonMapper json;

    public JobScheduleService(Rows rows, CrudEngine crud, CalendarProvider calendars, JobDispatcher dispatcher,
                              LockStore locks, ServiceCatalog catalog, OutboxWriter outbox, TransactionTemplate tx,
                              EodSettings settings, JsonMapper json) {
        this.rows = rows;
        this.crud = crud;
        this.calendars = calendars;
        this.dispatcher = dispatcher;
        this.locks = locks;
        this.catalog = catalog;
        this.outbox = outbox;
        this.tx = tx;
        this.settings = settings;
        this.json = json;
    }

    /** Validates a schedule (merged values) and sets {@code next_run_at}; returns the values to store. */
    public ObjectNode prepare(ObjectNode values) {
        CronExpression cron = cron(JsonValues.text(values, "cron_expression"));
        ZoneId zone = zone(values.hasNonNull("time_zone") ? values.get("time_zone").asString() : "UTC");
        String misfire = values.hasNonNull("misfire_policy") ? values.get("misfire_policy").asString() : "RUN_ONCE";
        if (!MISFIRE.contains(misfire)) {
            throw ApiException.unprocessable("invalid_misfire_policy", "misfire_policy must be one of " + MISFIRE);
        }
        String service = JsonValues.text(values, "owning_service");
        if (service != null && !catalog.exists(service)) {
            throw ApiException.unprocessable("unknown_service", "owning_service " + service + " is not a Meridian service");
        }
        String endpoint = JsonValues.text(values, "endpoint");
        if (endpoint != null && (!endpoint.startsWith("/") || endpoint.contains("://"))) {
            throw ApiException.unprocessable("invalid_endpoint", "endpoint must be a service-relative path starting with /");
        }
        String status = values.hasNonNull("status") ? values.get("status").asString() : "ACTIVE";
        if ("ACTIVE".equals(status)) {
            OffsetDateTime next = nextRun(cron, zone, values.path("business_days_only").asBoolean(false),
                    uuid(values, "holiday_calendar_id"), OffsetDateTime.now());
            values.put("next_run_at", next == null ? null : next.toString());
        } else {
            values.putNull("next_run_at");
        }
        return values;
    }

    /**
     * Next fire time strictly after {@code after}, in the schedule's zone; with {@code businessDaysOnly}, fire
     * times on non-business days of the calendar are skipped. Null when the expression never fires again.
     */
    public OffsetDateTime nextRun(CronExpression cron, ZoneId zone, boolean businessDaysOnly, UUID calendarId,
                                  OffsetDateTime after) {
        BusinessCalendar cal = businessDaysOnly ? calendars.calendar(calendarId) : null;
        ZonedDateTime t = after.atZoneSameInstant(zone);
        for (int i = 0; i < MAX_CRON_SCAN; i++) {
            t = cron.next(t);
            if (t == null) {
                return null;
            }
            if (cal == null || cal.isBusinessDay(t.toLocalDate())) {
                return t.toOffsetDateTime();
            }
        }
        return null;
    }

    public String pause(UUID id) {
        return tx.execute(s -> {
            JsonNode sch = rows.require("job_schedule", id, true);
            String status = sch.get("status").asString();
            if (!"ACTIVE".equals(status)) {
                throw DomainException.invalidTransition("Schedule", status, "be paused");
            }
            return rows.jdbc().sql("""
                    update job_schedule set status = 'PAUSED', next_run_at = null, updated_by = ? where id = ?
                    returning row_to_json(job_schedule)::text""")
                    .params(TenantContext.principal(), id).query(String.class).single();
        });
    }

    public String resume(UUID id) {
        return tx.execute(s -> {
            JsonNode sch = rows.require("job_schedule", id, true);
            String status = sch.get("status").asString();
            if (!"PAUSED".equals(status)) {
                throw DomainException.invalidTransition("Schedule", status, "be resumed");
            }
            ObjectNode v = ((ObjectNode) sch).deepCopy();
            v.put("status", "ACTIVE");
            prepare(v);
            return rows.jdbc().sql("""
                    update job_schedule set status = 'ACTIVE', next_run_at = ?::timestamptz, updated_by = ? where id = ?
                    returning row_to_json(job_schedule)::text""")
                    .params(v.path("next_run_at").asString(null), TenantContext.principal(), id)
                    .query(String.class).single();
        });
    }

    /**
     * Runs the job now: takes the schedule's lease lock (409 if a run is in progress), invokes the owning
     * service, records an immutable job_schedule_run and updates last/next run on the schedule.
     */
    public String runNow(UUID id, JsonNode parameters, String idempotencyKey) {
        JsonNode sch = rows.require("job_schedule", id, false);
        if ("DISABLED".equals(sch.get("status").asString())) {
            throw ApiException.conflict("schedule_disabled", "Schedule " + sch.get("code").asString() + " is disabled");
        }
        String lockKey = "schedule:" + id;
        String holder = settings.instanceId() + ":" + UUID.randomUUID();
        Duration lease = settings.lockLease();
        if (locks.acquire(lockKey, holder, lease).isEmpty()) {
            throw ApiException.conflict("schedule_already_running", "Schedule " + sch.get("code").asString() + " is already running");
        }
        try {
            OffsetDateTime started = OffsetDateTime.now();
            ObjectNode payload = json.createObjectNode();
            payload.put("job_schedule_id", id.toString());
            payload.put("code", sch.get("code").asString());
            payload.put("scheduled_for", started.toString());
            payload.put("trigger", "MANUAL");
            ObjectNode params = json.createObjectNode();
            if (sch.hasNonNull("parameters") && sch.get("parameters").isObject()) {
                params.setAll((ObjectNode) sch.get("parameters"));
            }
            if (parameters != null && parameters.isObject()) {
                params.setAll((ObjectNode) parameters);
            }
            payload.set("parameters", params);
            String status;
            String error = null;
            Long records = null;
            try {
                JsonNode res = dispatcher.dispatch(sch.get("owning_service").asString(), sch.get("endpoint").asString(),
                        payload, "jsr-" + id + "-" + idempotencyKey);
                status = "SUCCEEDED";
                if (res != null && res.hasNonNull("records_processed") && res.get("records_processed").isNumber()) {
                    records = res.get("records_processed").asLong();
                }
            } catch (ApiException e) {
                status = "FAILED";
                error = e.code() + ": " + e.getMessage();
            }
            String finalStatus = status;
            String finalError = error;
            Long finalRecords = records;
            return tx.execute(s -> {
                ObjectNode run = json.createObjectNode();
                run.put("job_schedule_id", id.toString());
                run.put("scheduled_for", started.toString());
                run.put("started_at", started.toString());
                run.put("finished_at", OffsetDateTime.now().toString());
                run.put("status", finalStatus);
                if (finalRecords != null) {
                    run.put("records_processed", finalRecords);
                }
                if (finalError != null) {
                    run.put("error", finalError);
                }
                String row = crud.insert("job_schedule_run", run);
                JsonNode current = rows.require("job_schedule", id, true);
                String next = null;
                if ("ACTIVE".equals(current.get("status").asString())) {
                    OffsetDateTime n = nextRun(cron(current.get("cron_expression").asString()),
                            zone(current.get("time_zone").asString()), current.path("business_days_only").asBoolean(false),
                            uuid(current, "holiday_calendar_id"), OffsetDateTime.now());
                    next = n == null ? null : n.toString();
                }
                rows.update("""
                        update job_schedule set last_run_at = ?::timestamptz, last_run_status = ?,
                               next_run_at = ?::timestamptz where id = ?""", started.toString(), finalStatus, next, id);
                JsonNode r = json.readTree(row);
                outbox.publish("operations.job_schedule_run.completed", "job_schedule_run",
                        UUID.fromString(r.get("id").asString()), r);
                return row;
            });
        } finally {
            locks.release(lockKey, holder);
        }
    }

    /** Force-releases a lock (operator action for a stale lease left by a crashed instance). */
    public void forceRelease(UUID lockId, String reason) {
        tx.executeWithoutResult(s -> {
            rows.require("batch_job_lock", lockId, true);
            if (reason == null || reason.isBlank()) {
                throw ApiException.unprocessable("reason_required", "A reason is required to force-release a lock");
            }
            locks.delete(lockId);
        });
    }

    static CronExpression cron(String expr) {
        if (expr == null || expr.isBlank()) {
            throw ApiException.unprocessable("invalid_cron", "cron_expression is required");
        }
        try {
            return CronExpression.parse(expr.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("invalid_cron", "Invalid cron expression '" + expr + "': " + e.getMessage());
        }
    }

    static ZoneId zone(String z) {
        try {
            return ZoneId.of(z);
        } catch (DateTimeException e) {
            throw ApiException.unprocessable("invalid_time_zone", "Unknown time zone " + z);
        }
    }

    private static UUID uuid(JsonNode n, String f) {
        return n.hasNonNull(f) ? UUID.fromString(n.get(f).asString()) : null;
    }
}
