package io.meridian.tenant.application;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.tenant.domain.Modules;
import io.meridian.tenant.infrastructure.TenantStore;
import io.meridian.web.client.ServiceClient;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Enabling and disabling platform modules under maker-checker: the maker's request becomes an approval request
 * in workflow-service (libs/meridian-approvals); the change is applied only when the approved request comes back
 * ({@link TenantModuleApprovalHandler} -> {@link #applyApproved(JsonNode)}), re-validating module dependencies at
 * execution time. There is no local decision endpoint, so module changes go to workflow-service whatever
 * {@code meridian.approvals.mode} says.
 */
@Service
public class ModuleService {
    public static final String RESOURCE_TYPE = "tenant_module";

    private final TenantStore store;
    private final Approvals approvals;
    private final Events events;
    private final JsonMapper json;

    public ModuleService(TenantStore store, ServiceClient client, @Value("${spring.application.name}") String application,
                         Events events, JsonMapper json) {
        this.store = store;
        this.approvals = new Approvals(client, application, "workflow");
        this.events = events;
        this.json = json;
    }

    /** Validates and files an approval request to enable the module. */
    public JsonNode requestEnable(UUID moduleId, JsonNode config, String idempotencyKey) {
        ObjectNode module = store.require("tenant_module", moduleId);
        String name = module.get("module").asString();
        if (module.get("is_enabled").asBoolean()) {
            throw ApiException.conflict("invalid_transition", "Module " + name + " is already enabled");
        }
        requireTenantServing();
        List<String> missing = Modules.missingPrerequisites(name, enabledModules());
        if (!missing.isEmpty()) {
            throw ApiException.unprocessable("module_dependency_missing",
                    "Enable " + String.join(", ", missing) + " before " + name);
        }
        ObjectNode payload = json.createObjectNode();
        payload.put("module_id", moduleId.toString());
        payload.put("module", name);
        payload.put("is_enabled", true);
        if (config != null && !config.isNull()) {
            payload.set("config", config);
        }
        return request(ApprovalSpec.of(RESOURCE_TYPE, moduleId, "enable", "Enable module " + name, payload)
                .withSummary("Enable the " + name + " module for the tenant"), "enable:" + moduleId + ":" + idempotencyKey);
    }

    /** Validates and files an approval request to disable the module. */
    public JsonNode requestDisable(UUID moduleId, String reason, String idempotencyKey) {
        ObjectNode module = store.require("tenant_module", moduleId);
        String name = module.get("module").asString();
        if (!module.get("is_enabled").asBoolean()) {
            throw ApiException.conflict("invalid_transition", "Module " + name + " is already disabled");
        }
        List<String> blocking = Modules.blockingDependents(name, enabledModules());
        if (!blocking.isEmpty()) {
            throw ApiException.unprocessable("module_has_dependents",
                    "Disable " + String.join(", ", blocking) + " before " + name);
        }
        ObjectNode payload = json.createObjectNode();
        payload.put("module_id", moduleId.toString());
        payload.put("module", name);
        payload.put("is_enabled", false);
        payload.put("reason", reason);
        return request(ApprovalSpec.of(RESOURCE_TYPE, moduleId, "disable", "Disable module " + name, payload)
                .withSummary(reason), "disable:" + moduleId + ":" + idempotencyKey);
    }

    private JsonNode request(ApprovalSpec spec, String key) {
        JsonNode created = approvals.request(spec, key);
        if (created == null || !created.hasNonNull("id")) {
            throw new ApiException(502, "upstream_error", "workflow-service did not return the created approval request");
        }
        return created;
    }

    /**
     * Executes an approved module change. Idempotent: re-delivery of the same approval is a no-op once the module
     * is in the requested state.
     */
    @Transactional
    public JsonNode applyApproved(JsonNode approval) {
        if (!RESOURCE_TYPE.equals(approval.path("resource_type").asString())) {
            throw ApiException.unprocessable("wrong_resource_type", "Approval is not for a tenant module");
        }
        String status = approval.path("status").asString();
        if (!"APPROVED".equals(status)) {
            throw ApiException.conflict("approval_not_granted", "Approval request is " + status);
        }
        String maker = approval.path("maker").asString(approval.path("created_by").asString(null));
        io.meridian.common.tenancy.FourEyes.require(maker, "The maker cannot execute their own approval");
        JsonNode payload = approval.path("payload");
        UUID moduleId = UUID.fromString(payload.path("module_id").asString(approval.path("resource_id").asString()));
        boolean enable = payload.path("is_enabled").asBoolean();
        ObjectNode module = store.lock("tenant_module", moduleId);
        String name = module.get("module").asString();
        if (module.get("is_enabled").asBoolean() == enable) {
            return module;
        }
        Set<String> enabled = enabledModules();
        if (enable) {
            List<String> missing = Modules.missingPrerequisites(name, enabled);
            if (!missing.isEmpty()) {
                throw ApiException.unprocessable("module_dependency_missing",
                        "Enable " + String.join(", ", missing) + " before " + name);
            }
        } else {
            List<String> blocking = Modules.blockingDependents(name, enabled);
            if (!blocking.isEmpty()) {
                throw ApiException.unprocessable("module_has_dependents",
                        "Disable " + String.join(", ", blocking) + " before " + name);
            }
        }
        ObjectNode values = json.createObjectNode();
        values.put("is_enabled", enable);
        if (approval.hasNonNull("id")) {
            values.put("approval_request_id", approval.get("id").asString());
        }
        if (enable) {
            values.put("enabled_at", io.meridian.common.time.Clocks.now().toString());
            if (payload.hasNonNull("config")) {
                values.set("config", payload.get("config"));
            }
        } else {
            values.putNull("enabled_at");
        }
        ObjectNode row = store.update("tenant_module", moduleId, values);
        events.publish("platform.tenant_module.changed", "tenant_module", moduleId, row);
        return row;
    }

    private Set<String> enabledModules() {
        Set<String> s = new HashSet<>();
        store.query("select row_to_json(m)::text from tenant_module m where is_enabled")
                .forEach(m -> s.add(m.get("module").asString()));
        return s;
    }

    /** A suspended or terminated tenant cannot change its modules (when its tenant record is known here). */
    private void requireTenantServing() {
        store.find("tenant", TenantContext.require()).ifPresent(t -> {
            String status = t.get("status").asString();
            if (!Set.of("PROVISIONING", "ACTIVE").contains(status)) {
                throw ApiException.conflict("tenant_not_active", "Tenant is " + status);
            }
        });
    }
}
