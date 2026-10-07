package io.meridian.tenant.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.tenant.domain.PackTemplates;
import io.meridian.tenant.domain.StarterPack;
import io.meridian.tenant.infrastructure.StarterPackStore;
import io.meridian.tenant.infrastructure.TenantStore;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Applies tenant starter packs. Runs execute on a background virtual thread with the target tenant bound in
 * {@link TenantContext}: outside the HTTP request, {@code ServiceClient} authenticates with the service identity
 * (client credentials, {@code meridian:internal}) and delegates to the tenant with {@code X-Tenant-Id}, so a platform
 * operator can bootstrap another tenant without a token of that tenant. The acting principal (the operator or
 * provisioning user) travels as {@code X-User}, so it is the maker of every approval request the pack opens and
 * cannot approve them itself.
 *
 * <p>API calls wait for the run up to {@code meridian.starter-packs.sync-timeout-seconds} and return the run as it is
 * then; provisioning starts the run after commit and returns immediately.
 */
@Service
public class StarterPackService implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(StarterPackService.class);
    private static final Set<String> RESUMABLE = Set.of("FAILED", "AWAITING_APPROVAL", "NEEDS_ATTENTION");
    private static final Set<String> NOT_SERVING = Set.of("OFFBOARDING", "TERMINATED");
    private static final int STALE_MINUTES = 15;

    private final StarterPackCatalog catalog;
    private final StarterPackEngine engine;
    private final StarterPackStore store;
    private final TenantStore tenants;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final long syncTimeoutSeconds;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public StarterPackService(StarterPackCatalog catalog, StarterPackEngine engine, StarterPackStore store,
                              TenantStore tenants, TransactionTemplate tx, JsonMapper json,
                              @Value("${meridian.starter-packs.sync-timeout-seconds:120}") long syncTimeoutSeconds) {
        this.catalog = catalog;
        this.engine = engine;
        this.store = store;
        this.tenants = tenants;
        this.tx = tx;
        this.json = json;
        this.syncTimeoutSeconds = syncTimeoutSeconds;
    }

    public StarterPackCatalog catalog() {
        return catalog;
    }

    /** The caller's own tenant (tenant administrator). */
    public JsonNode applyToCaller(String packCode, String version, JsonNode parameters, boolean dryRun) {
        return apply(TenantContext.require(), packCode, version, parameters, dryRun, "SELF_SERVICE", null, true);
    }

    /** A platform operator applies a pack to any serving tenant. */
    public JsonNode applyToTenant(UUID tenantId, String packCode, String version, JsonNode parameters, boolean dryRun) {
        ObjectNode tenant = tenants.require("tenant", tenantId);
        if (NOT_SERVING.contains(tenant.path("status").asString())) {
            throw ApiException.unprocessable("tenant_not_serving", "Tenant is " + tenant.path("status").asString());
        }
        return apply(tenantId, packCode, version, parameters, dryRun, "OPERATOR", null, true);
    }

    /** Re-applies a run's pack version with its resolved parameters (the run row belongs to the caller's tenant). */
    public JsonNode resume(UUID runId) {
        UUID tenant = TenantContext.require();
        ObjectNode run = store.run(runId).orElseThrow(() -> ApiException.notFound("starter pack run", runId));
        String status = run.path("status").asString();
        if (run.path("dry_run").asBoolean() || !RESUMABLE.contains(status)) {
            throw ApiException.conflict("invalid_transition", "Only a FAILED, AWAITING_APPROVAL or NEEDS_ATTENTION run "
                    + "(not a dry run) can be resumed; this run is " + status);
        }
        return apply(tenant, run.get("pack_code").asString(), run.get("pack_version").asString(), run.get("parameters"),
                false, "RESUME", runId, true);
    }

    /**
     * Validates a pack requested at provisioning (unknown pack or parameters fail the provisioning request) and
     * starts it after the provisioning transaction commits.
     */
    public void applyAfterProvisioning(UUID tenantId, ObjectNode tenantRow, String packCode, JsonNode parameters) {
        StarterPack pack = catalog.require(packCode, null);
        resolveParameters(pack, tenantRow, parameters);
        String principal = TenantContext.principal();
        Runnable start = () -> {
            try {
                ObjectNode run = TenantContext.callAs(tenantId, principal, () -> apply(tenantId, pack.code(), pack.version(),
                        parameters, false, "PROVISIONING", null, false));
                ObjectNode v = json.createObjectNode();
                v.put("starter_pack_run_id", run.get("id").asString());
                tenants.update("tenant", tenantId, v);
            } catch (Exception e) {
                log.error("starter pack {} could not be started for tenant {}", packCode, tenantId, e);
            }
        };
        // on another thread: the committed provisioning transaction's connection (scoped to the operator's tenant)
        // is still bound to this one during afterCommit
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor.submit(start);
                }
            });
        } else {
            executor.submit(start);
        }
    }

    // ------------------------------------------------------------------ core

    ObjectNode apply(UUID tenantId, String packCode, String version, JsonNode requested, boolean dryRun, String trigger,
                     UUID resumedFrom, boolean wait) {
        StarterPack pack = catalog.require(packCode, version);
        ObjectNode tenantRow = tenants.find("tenant", tenantId).orElse(null);
        Map<String, JsonNode> params = resolveParameters(pack, tenantRow, requested);
        String principal = TenantContext.principal();
        String today = today(params);
        ObjectNode run = call(tenantId, principal, () -> tx.execute(s -> {
            if (!dryRun) {
                store.lockAndFindActive(pack.code(), STALE_MINUTES).ifPresent(r -> {
                    throw ApiException.conflict("starter_pack_run_in_progress", "Run " + r.get("id").asString() + " of "
                            + pack.code() + " is still running for this tenant");
                });
            }
            ObjectNode v = json.createObjectNode();
            v.put("pack_code", pack.code());
            v.put("pack_version", pack.version());
            v.put("status", "RUNNING");
            v.put("trigger", trigger);
            v.set("parameters", json.valueToTree(params));
            v.put("dry_run", dryRun);
            if (resumedFrom != null) {
                v.put("resumed_from_id", resumedFrom.toString());
            }
            v.put("started_at", io.meridian.common.time.Clocks.now().toString());
            v.putArray("results");
            v.putArray("approval_request_ids");
            return store.insertRun(v);
        }));
        UUID runId = UUID.fromString(run.get("id").asString());
        Future<ObjectNode> f = executor.submit(() -> call(tenantId, principal,
                () -> engine.execute(pack, params, runId, dryRun, today)));
        if (!wait) {
            return run;
        }
        try {
            return f.get(syncTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return call(tenantId, principal, () -> store.run(runId).orElse(run)); // still RUNNING: poll GET /{id}
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "interrupted", "Interrupted while waiting for the starter pack run");
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException re ? re : new IllegalStateException(e.getCause());
        }
    }

    /**
     * Pack defaults, then the tenant's own facts for parameters that declare a {@code tenant_fact} (its currency,
     * country, legal name, time zone), then the request. Unknown names and wrong types are rejected.
     */
    public static Map<String, JsonNode> resolveParameters(StarterPack pack, JsonNode tenantRow, JsonNode requested) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        pack.parameters().forEach((k, p) -> {
            JsonNode v = p.defaultValue();
            if (tenantRow != null && p.tenantFact() != null && tenantRow.hasNonNull(p.tenantFact())) {
                v = tenantRow.get(p.tenantFact());
            }
            out.put(k, v);
        });
        if (requested != null && !requested.isNull()) {
            if (!requested.isObject()) {
                throw ApiException.unprocessable("invalid_parameters", "parameters must be an object");
            }
            for (Map.Entry<String, JsonNode> e : requested.properties()) {
                StarterPack.Param p = pack.parameters().get(e.getKey());
                if (p == null) {
                    throw ApiException.unprocessable("unknown_parameter", "Starter pack " + pack.code()
                            + " has no parameter " + e.getKey() + "; parameters: " + String.join(", ", pack.parameters().keySet()));
                }
                if (!PackTemplates.matchesType(e.getValue(), p.type())) {
                    throw ApiException.unprocessable("invalid_parameter", "Parameter " + e.getKey() + " must be a " + p.type());
                }
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    /** Business "today" for validity dates, in the pack's timezone parameter (UTC when absent or invalid). */
    static String today(Map<String, JsonNode> params) {
        ZoneId zone = java.time.ZoneOffset.UTC;
        JsonNode tz = params.get("timezone");
        if (tz != null && tz.isString()) {
            try {
                zone = ZoneId.of(tz.asString());
            } catch (DateTimeException e) {
                // keep UTC
            }
        }
        return io.meridian.common.time.Clocks.now().atZoneSameInstant(zone).toLocalDate().toString();
    }

    private static <T> T call(UUID tenant, String principal, java.util.concurrent.Callable<T> body) {
        try {
            return TenantContext.callAs(tenant, principal, body);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void destroy() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(20, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
