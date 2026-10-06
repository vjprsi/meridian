package io.meridian.businessday.application;

import io.meridian.businessday.domain.BusinessDayState;
import io.meridian.businessday.domain.EodTrigger;
import io.meridian.businessday.infrastructure.EodSweepStore;
import io.meridian.businessday.infrastructure.Rows;
import io.meridian.businessday.infrastructure.TenantDirectory;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The cross-tenant EOD trigger. A sweep lists the ACTIVE tenants from tenant-service and evaluates each one under
 * its own tenant context (row-level security applies): for every legal entity with a current business date it
 * resolves the effective {@link EodTrigger.Policy}, checks the cut-off against the database clock and starts EOD
 * (or rolls the date when the policy allows it after a successful EOD). Tenants run in parallel on virtual threads
 * with a per-tenant timeout; a failing or hanging tenant is recorded and never blocks the others. Sweeps are
 * de-duplicated by slot key across instances; starting EOD is idempotent per legal entity and business date
 * (a date with an EOD run is never started again).
 */
@Service
public class EodTriggerService {
    private static final Logger log = LoggerFactory.getLogger(EodTriggerService.class);

    private final Rows rows;
    private final EodRunService eod;
    private final BusinessDayService days;
    private final TenantDirectory tenants;
    private final EodSweepStore sweeps;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final AutoEodSettings settings;
    private final MeterRegistry meters;
    private final JsonMapper json;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public EodTriggerService(Rows rows, EodRunService eod, BusinessDayService days, TenantDirectory tenants,
                             EodSweepStore sweeps, OutboxWriter outbox, TransactionTemplate tx, AutoEodSettings settings,
                             MeterRegistry meters, JsonMapper json) {
        this.rows = rows;
        this.eod = eod;
        this.days = days;
        this.tenants = tenants;
        this.sweeps = sweeps;
        this.outbox = outbox;
        this.tx = tx;
        this.settings = settings;
        this.meters = meters;
        this.json = json;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** Scheduler slot of an instant: equal for every instance ticking within the same interval. */
    public String schedulerSlot(OffsetDateTime at) {
        long seconds = Math.max(1, settings.interval().toSeconds());
        return "sched:" + (at.toEpochSecond() / seconds) + "x" + seconds;
    }

    /** One scheduler tick: claims the current slot (other instances skip it) and sweeps every ACTIVE tenant. */
    public Optional<String> tick() {
        OffsetDateTime now = sweeps.dbNow();
        return sweep("SCHEDULER", schedulerSlot(now), AutoEodSettings.SCHEDULER, null);
    }

    /**
     * Runs a sweep in a fresh thread (no inbound request: downstream calls use the service token and the evaluated
     * tenant). Returns the finished sweep row, or empty when the slot was already claimed.
     */
    public Optional<String> sweep(String trigger, String slotKey, String requestedBy, List<UUID> tenantIds) {
        Future<Optional<String>> f = executor.submit(() -> doSweep(trigger, slotKey, requestedBy, tenantIds));
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "interrupted", "EOD sweep interrupted");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    /** The finished (or running) sweep of a slot key. */
    public Optional<String> sweepBySlot(String slotKey) {
        return sweeps.bySlot(slotKey);
    }

    private Optional<String> doSweep(String trigger, String slotKey, String requestedBy, List<UUID> tenantIds) {
        Timer.Sample sample = Timer.start(meters);
        OffsetDateTime asOf = sweeps.dbNow();
        Optional<UUID> claimed = sweeps.claim(trigger, slotKey, asOf, requestedBy, tenantIds);
        if (claimed.isEmpty()) {
            log.debug("EOD sweep slot {} already claimed", slotKey);
            meters.counter("meridian.eod.sweep.skipped", "trigger", trigger).increment();
            return Optional.empty();
        }
        UUID sweepId = claimed.get();
        ArrayNode results = json.createArrayNode();
        String status;
        String error = null;
        int[] counts = new int[5]; // tenants, failed tenants, started, rolled, skipped
        List<TenantDirectory.Tenant> targets;
        try {
            targets = TenantContext.callAs(settings.platformTenant(), requestedBy, tenants::activeTenants);
        } catch (Exception e) {
            log.error("EOD sweep {}: cannot list tenants", sweepId, e);
            targets = null;
            error = "Cannot list ACTIVE tenants from tenant-service: " + e.getMessage();
        }
        if (targets == null) {
            status = "FAILED";
        } else {
            if (tenantIds != null && !tenantIds.isEmpty()) {
                List<TenantDirectory.Tenant> filtered = new ArrayList<>(targets.stream()
                        .filter(t -> tenantIds.contains(t.id())).toList());
                tenantIds.stream().filter(id -> filtered.stream().noneMatch(t -> t.id().equals(id))).forEach(id ->
                        results.addObject().put("tenant_id", id.toString()).put("decision", "SKIPPED")
                                .put("error_code", "tenant_not_active").put("error", "Tenant is not ACTIVE"));
                targets = filtered;
            }
            runTenants(sweepId, asOf, targets, results, counts);
            status = counts[1] == 0 ? "SUCCEEDED" : counts[1] < counts[0] ? "PARTIALLY_SUCCEEDED" : "FAILED";
            if (counts[1] > 0) {
                error = counts[1] + " of " + counts[0] + " tenants failed";
            }
        }
        String row = sweeps.finish(sweepId, status, counts[0], counts[1], counts[2], counts[3], counts[4], results,
                error);
        publishPlatform(sweepId, row);
        sample.stop(meters.timer("meridian.eod.sweep.duration", "trigger", trigger, "status", status));
        meters.counter("meridian.eod.sweep.runs", "trigger", trigger, "status", status).increment();
        log.info("EOD sweep {} ({}) {}: tenants={} failed={} started={} rolled={} skipped={}", sweepId, trigger, status,
                counts[0], counts[1], counts[2], counts[3], counts[4]);
        return Optional.of(row);
    }

    private void runTenants(UUID sweepId, OffsetDateTime asOf, List<TenantDirectory.Tenant> targets, ArrayNode results,
                            int[] counts) {
        Semaphore permits = new Semaphore(settings.tenantConcurrency());
        Map<TenantDirectory.Tenant, Future<List<ObjectNode>>> futures = new LinkedHashMap<>();
        for (TenantDirectory.Tenant t : targets) {
            futures.put(t, executor.submit(() -> {
                permits.acquire();
                try {
                    return TenantContext.callAs(t.id(), AutoEodSettings.SCHEDULER, () -> evaluateTenant(t, asOf, true));
                } finally {
                    permits.release();
                }
            }));
        }
        // tenants queue behind the concurrency limit: the overall budget is one timeout per "wave" of tenants
        long waves = Math.max(1, (targets.size() + settings.tenantConcurrency() - 1) / settings.tenantConcurrency());
        long deadline = System.nanoTime() + settings.tenantTimeout().toNanos() * waves;
        for (Map.Entry<TenantDirectory.Tenant, Future<List<ObjectNode>>> e : futures.entrySet()) {
            TenantDirectory.Tenant t = e.getKey();
            counts[0]++;
            List<ObjectNode> entityResults;
            try {
                long wait = Math.max(TimeUnit.MILLISECONDS.toNanos(1), deadline - System.nanoTime());
                entityResults = e.getValue().get(wait, TimeUnit.NANOSECONDS);
            } catch (TimeoutException ex) {
                e.getValue().cancel(true);
                entityResults = List.of(tenantFailure(t, "timeout", "Tenant evaluation did not finish within "
                        + settings.tenantTimeout()));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                entityResults = List.of(tenantFailure(t, "interrupted", "Sweep interrupted"));
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause();
                String code = cause instanceof ApiException api ? api.code() : "tenant_error";
                log.warn("EOD sweep {}: tenant {} failed", sweepId, t.id(), cause);
                entityResults = List.of(tenantFailure(t, code, String.valueOf(cause.getMessage())));
            }
            boolean failed = false;
            for (ObjectNode r : entityResults) {
                results.add(r);
                String decision = r.path("decision").asString();
                meters.counter("meridian.eod.trigger.decisions", "decision", decision).increment();
                switch (decision) {
                    case "START" -> counts[2]++;
                    case "ROLL" -> counts[3]++;
                    case "FAILED" -> failed = true;
                    default -> counts[4]++;
                }
            }
            if (failed) {
                counts[1]++;
                publishTenantFailure(sweepId, t, entityResults);
            }
        }
    }

    private ObjectNode tenantFailure(TenantDirectory.Tenant t, String code, String message) {
        ObjectNode r = json.createObjectNode();
        r.put("tenant_id", t.id().toString());
        if (t.code() != null) {
            r.put("tenant_code", t.code());
        }
        r.put("decision", EodTrigger.Decision.FAILED.name());
        r.put("error_code", code);
        r.put("error", truncate(message));
        return r;
    }

    /**
     * Evaluates every current business date of the bound tenant; with {@code execute} starts EOD / rolls dates,
     * otherwise only reports what would happen. Failures of one legal entity are recorded and the others proceed.
     */
    public List<ObjectNode> evaluateTenant(TenantDirectory.Tenant tenant, OffsetDateTime asOf, boolean execute) {
        List<ObjectNode> out = new ArrayList<>();
        List<JsonNode> current = rows.query("""
                select row_to_json(b)::text from business_day b where is_current order by legal_entity_id""");
        Map<String, JsonNode> policies = new LinkedHashMap<>();
        rows.query("select row_to_json(p)::text from eod_trigger_policy p")
                .forEach(p -> policies.put(p.path("legal_entity_id").asString(""), p));
        boolean hasJobs = rows.jdbc().sql("select count(*) from eod_job_definition where is_active")
                .query(Long.class).single() > 0;
        for (JsonNode day : current) {
            ObjectNode r = json.createObjectNode();
            r.put("tenant_id", tenant.id().toString());
            if (tenant.code() != null) {
                r.put("tenant_code", tenant.code());
            }
            UUID entity = UUID.fromString(day.get("legal_entity_id").asString());
            UUID dayId = UUID.fromString(day.get("id").asString());
            r.put("legal_entity_id", entity.toString());
            r.put("business_day_id", dayId.toString());
            r.put("business_date", day.get("business_date").asString());
            r.put("state", day.get("state").asString());
            try {
                EodTrigger.Policy policy = policy(tenant, entity, policies);
                r.put("policy", policy.source());
                r.put("cut_off_time", policy.cutOff().toString());
                r.put("time_zone", policy.zone().getId());
                r.put("auto_roll", policy.autoRoll());
                OffsetDateTime dueAt = EodTrigger.dueAt(LocalDate.parse(day.get("business_date").asString()),
                        policy.cutOff(), policy.zone());
                r.put("due_at", dueAt.toString());
                JsonNode latest = rows.queryOne("""
                        select row_to_json(e)::text from eod_run e where business_day_id = ?
                        order by created_at desc, id desc limit 1""", dayId).orElse(null);
                if (latest != null) {
                    r.put("eod_run_id", latest.get("id").asString());
                    r.put("eod_run_status", latest.get("status").asString());
                }
                EodTrigger.Decision decision = EodTrigger.decide(policy, BusinessDayState.of(day.get("state").asString()),
                        latest == null ? null : latest.get("status").asString(), hasJobs, !asOf.isBefore(dueAt));
                r.put("decision", decision.name());
                if (execute && decision == EodTrigger.Decision.START) {
                    start(dayId, r);
                } else if (execute && decision == EodTrigger.Decision.ROLL) {
                    roll(dayId, r);
                }
            } catch (ApiException e) {
                r.put("decision", EodTrigger.Decision.FAILED.name());
                r.put("error_code", e.code());
                r.put("error", truncate(e.getMessage()));
            } catch (RuntimeException e) {
                log.warn("EOD trigger failed for tenant {} legal entity {}", tenant.id(), entity, e);
                r.put("decision", EodTrigger.Decision.FAILED.name());
                r.put("error_code", "trigger_error");
                r.put("error", truncate(String.valueOf(e.getMessage())));
            }
            out.add(r);
        }
        return out;
    }

    private void start(UUID dayId, ObjectNode r) {
        try {
            JsonNode run = json.readTree(eod.start(dayId, null));
            r.put("eod_run_id", run.get("id").asString());
            r.put("eod_run_status", run.get("status").asString());
            r.put("cycle_type", run.get("cycle_type").asString());
        } catch (ApiException e) {
            if (e.status() == 409) {
                // another instance or an operator started (or locked) the cycle in the meantime: nothing to do
                r.put("decision", EodTrigger.Decision.ALREADY_RAN.name());
                r.put("error_code", e.code());
                return;
            }
            throw e;
        }
    }

    private void roll(UUID dayId, ObjectNode r) {
        try {
            JsonNode roll = json.readTree(days.roll(dayId, false, "Automatic date roll after a successful EOD"));
            r.put("to_business_date", roll.get("to_business_date").asString());
        } catch (ApiException e) {
            if (e.status() == 409) {
                r.put("decision", EodTrigger.Decision.ALREADY_RAN.name());
                r.put("error_code", e.code());
                return;
            }
            throw e;
        }
    }

    /**
     * Effective policy of a legal entity: its own policy, else the tenant default (no legal entity), else the
     * platform default. Time zone: the policy's, else the legal entity's, else the tenant's, else the platform default.
     */
    EodTrigger.Policy policy(TenantDirectory.Tenant tenant, UUID entity, Map<String, JsonNode> policies) {
        JsonNode p = policies.get(entity.toString());
        String source = "LEGAL_ENTITY";
        if (p == null) {
            p = policies.get("");
            source = "TENANT_DEFAULT";
        }
        boolean enabled = p == null ? settings.defaultEnabled() : p.path("is_enabled").asBoolean(true);
        java.time.LocalTime cutOff = p == null ? settings.defaultCutOff()
                : EodTrigger.parseCutOff(p.path("cut_off_time").asString());
        boolean autoRoll = p != null && p.path("auto_roll").asBoolean(false);
        String zoneText = p == null ? null : p.path("time_zone").asString(null);
        ZoneId zone;
        if (zoneText != null && !zoneText.isBlank()) {
            zone = EodTrigger.parseZone(zoneText);
        } else {
            zone = tenants.legalEntityTimezone(entity).or(() -> Optional.ofNullable(tenant.timezone()))
                    .map(z -> {
                        try {
                            return EodTrigger.parseZone(z);
                        } catch (ApiException e) {
                            return null;
                        }
                    })
                    .orElse(settings.defaultZone());
        }
        return new EodTrigger.Policy(enabled, cutOff, zone, autoRoll, p == null ? "PLATFORM_DEFAULT" : source);
    }

    /** Dry-run evaluation of the bound tenant (operators' "what would the trigger do now"). */
    public ObjectNode evaluation() {
        UUID tenantId = TenantContext.require();
        OffsetDateTime asOf = sweeps.dbNow();
        TenantDirectory.Tenant tenant = tenants.tenant(tenantId).orElse(new TenantDirectory.Tenant(tenantId, null, null));
        ObjectNode out = json.createObjectNode();
        out.put("as_of", asOf.toString());
        out.put("tenant_id", tenantId.toString());
        ArrayNode entities = out.putArray("entities");
        evaluateTenant(tenant, asOf, false).forEach(r -> {
            r.remove("tenant_id");
            r.remove("tenant_code");
            entities.add(r);
        });
        return out;
    }

    private void publishPlatform(UUID sweepId, String row) {
        try {
            TenantContext.callAs(settings.platformTenant(), AutoEodSettings.SCHEDULER, () -> {
                tx.executeWithoutResult(s -> outbox.publish("operations.eod_sweep.completed", "eod_sweep", sweepId,
                        json.readTree(row)));
                return null;
            });
        } catch (Exception e) {
            log.warn("EOD sweep {}: completion event not written", sweepId, e);
        }
    }

    private void publishTenantFailure(UUID sweepId, TenantDirectory.Tenant t, List<ObjectNode> entityResults) {
        try {
            ObjectNode payload = json.createObjectNode();
            payload.put("eod_sweep_id", sweepId.toString());
            payload.put("tenant_id", t.id().toString());
            ArrayNode a = payload.putArray("results");
            entityResults.forEach(a::add);
            TenantContext.callAs(t.id(), AutoEodSettings.SCHEDULER, () -> {
                tx.executeWithoutResult(s -> outbox.publish("operations.eod_sweep.tenant_failed", "eod_sweep", sweepId,
                        payload));
                return null;
            });
        } catch (Exception e) {
            log.warn("EOD sweep {}: tenant_failed event for {} not written", sweepId, t.id(), e);
        }
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 1000 ? s : s.substring(0, 1000);
    }
}
