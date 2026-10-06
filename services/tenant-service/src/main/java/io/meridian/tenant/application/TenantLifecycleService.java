package io.meridian.tenant.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.tenant.domain.Lifecycle;
import io.meridian.tenant.domain.Modules;
import io.meridian.tenant.domain.SettingValues;
import io.meridian.tenant.domain.TenantTemplate;
import io.meridian.tenant.domain.Validators;
import io.meridian.tenant.infrastructure.TenantStore;
import io.meridian.web.crud.CrudEngine;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tenant lifecycle (provision, activate, suspend, reinstate), placement on cells (initial placement, cell-to-cell
 * migration, placement history) and cell draining. Tenants and cells are platform-global rows (no RLS).
 */
@Service
public class TenantLifecycleService {
    private static final Set<String> MIGRATABLE = Set.of("PROVISIONING", "ACTIVE", "SUSPENDED");

    private final TenantStore store;
    private final CrudEngine crud;
    private final Events events;
    private final JsonMapper json;

    public TenantLifecycleService(TenantStore store, CrudEngine crud, Events events, JsonMapper json) {
        this.store = store;
        this.crud = crud;
        this.events = events;
        this.json = json;
    }

    // ------------------------------------------------------------------ tenants

    /** Generic create with business rules: starts PROVISIONING, optional initial placement, module catalogue seeded. */
    @Transactional
    public JsonNode create(Object body) {
        ObjectNode values = crud.toColumns("tenant", body);
        requireCurrency(values.path("default_currency").asString(null));
        UUID tenantId = Ids.newId();
        values.put("id", tenantId.toString());
        values.put("status", "PROVISIONING");
        UUID cellId = values.hasNonNull("cell_id") ? UUID.fromString(values.get("cell_id").asString()) : null;
        if (cellId != null) {
            ObjectNode cell = reserveCapacity(cellId);
            if (!values.hasNonNull("home_region")) {
                values.put("home_region", cell.get("region").asString());
            }
        }
        ObjectNode tenant = store.insert("tenant", values);
        if (cellId != null) {
            placement(tenantId, cellId, null, "ACTIVE", io.meridian.common.time.Clocks.now(), "initial placement");
        }
        seedTenant(tenantId, null, values.path("default_currency").asString());
        events.publishForTenant(tenantId, "platform.tenant.provisioned", "tenant", tenantId, tenant);
        return tenant;
    }

    /** Provisions a tenant from a template bank: auto-placement on the least loaded shared cell and seeded defaults. */
    @Transactional
    public JsonNode provisionFromTemplate(JsonNode req) {
        TenantTemplate template = TenantTemplate.find(req.path("template_code").asString(null))
                .orElseThrow(() -> ApiException.unprocessable("unknown_template",
                        "Unknown template_code; available: " + String.join(", ", TenantTemplate.codes())));
        requireCurrency(req.path("default_currency").asString(null));
        if (!store.query("select row_to_json(t)::text from tenant t where code = ?", req.get("code").asString()).isEmpty()) {
            throw ApiException.conflict("duplicate", "Tenant code " + req.get("code").asString() + " already exists");
        }
        ObjectNode cell = store.query("""
                select row_to_json(c)::text from cell c
                where status = 'ACTIVE' and not is_dedicated and tenant_count < capacity_tenants
                order by tenant_count::numeric / capacity_tenants, id limit 1 for update""")
                .stream().findFirst()
                .orElseThrow(() -> ApiException.unprocessable("no_cell_capacity",
                        "No active shared cell has capacity for a new tenant"));
        UUID cellId = UUID.fromString(cell.get("id").asString());
        reserveCapacity(cellId);
        UUID tenantId = Ids.newId();
        ObjectNode values = json.createObjectNode();
        values.put("id", tenantId.toString());
        for (String f : new String[] {"code", "name", "legal_name", "tenant_type", "default_currency", "default_country",
                "primary_contact_email"}) {
            values.set(f, req.get(f));
        }
        values.put("status", "PROVISIONING");
        values.put("cell_id", cellId.toString());
        values.put("home_region", cell.get("region").asString());
        values.put("template_code", template.code());
        ObjectNode tenant = store.insert("tenant", values);
        placement(tenantId, cellId, null, "ACTIVE", io.meridian.common.time.Clocks.now(), "initial placement (template " + template.code() + ")");
        seedTenant(tenantId, template, req.get("default_currency").asString());
        events.publishForTenant(tenantId, "platform.tenant.provisioned", "tenant", tenantId, tenant);
        return tenant;
    }

    @Transactional
    public JsonNode activate(UUID id) {
        ObjectNode t = store.lock("tenant", id);
        String status = t.get("status").asString();
        if (!"PROVISIONING".equals(status)) {
            throw ApiException.conflict("invalid_transition", "Only a PROVISIONING tenant can be activated (tenant is "
                    + status + "); use reinstate for a suspended tenant");
        }
        if (!t.hasNonNull("cell_id")) {
            throw ApiException.unprocessable("tenant_not_placed", "The tenant must be placed on a cell before activation");
        }
        ObjectNode cell = store.require("cell", UUID.fromString(t.get("cell_id").asString()));
        if (!Set.of("ACTIVE", "DRAINING").contains(cell.get("status").asString())) {
            throw ApiException.unprocessable("cell_not_serving", "Home cell is " + cell.get("status").asString());
        }
        ObjectNode values = json.createObjectNode();
        values.put("status", "ACTIVE");
        values.put("activated_at", io.meridian.common.time.Clocks.now().toString());
        ObjectNode row = store.update("tenant", id, values);
        events.publishForTenant(id, "platform.tenant.activated", "tenant", id, row);
        return row;
    }

    @Transactional
    public JsonNode suspend(UUID id, String reason) {
        ObjectNode t = store.lock("tenant", id);
        String status = t.get("status").asString();
        if (!"ACTIVE".equals(status)) {
            throw ApiException.conflict("invalid_transition", "Only an ACTIVE tenant can be suspended (tenant is " + status + ")");
        }
        ObjectNode values = json.createObjectNode();
        values.put("status", "SUSPENDED");
        values.put("suspended_at", io.meridian.common.time.Clocks.now().toString());
        values.put("suspension_reason", reason);
        ObjectNode row = store.update("tenant", id, values);
        events.publishForTenant(id, "platform.tenant.suspended", "tenant", id, row);
        return row;
    }

    @Transactional
    public JsonNode reinstate(UUID id, String reason) {
        ObjectNode t = store.lock("tenant", id);
        String status = t.get("status").asString();
        if (!"SUSPENDED".equals(status)) {
            throw ApiException.conflict("invalid_transition", "Only a SUSPENDED tenant can be reinstated (tenant is " + status + ")");
        }
        ObjectNode values = json.createObjectNode();
        values.put("status", "ACTIVE");
        values.putNull("suspended_at");
        values.putNull("suspension_reason");
        values.put("reinstatement_reason", reason);
        values.put("reinstated_at", io.meridian.common.time.Clocks.now().toString());
        ObjectNode row = store.update("tenant", id, values);
        events.publishForTenant(id, "platform.tenant.reinstated", "tenant", id, row);
        return row;
    }

    /** Rejects cell changes through PATCH: they go through /migrate (status is read-only). */
    public void checkTenantPatch(UUID id, Object body) {
        ObjectNode values = crud.toColumns("tenant", body);
        ObjectNode current = store.require("tenant", id);
        if (values.hasNonNull("cell_id") && !values.get("cell_id").asString().equals(current.path("cell_id").asString(""))) {
            throw ApiException.conflict("use_migrate_action", "Move a tenant between cells with /migrate");
        }
        if (values.hasNonNull("default_currency")) {
            requireCurrency(values.get("default_currency").asString());
        }
    }

    // ------------------------------------------------------------------ placement / migration

    @Transactional
    public JsonNode migrate(UUID tenantId, UUID targetCellId, OffsetDateTime scheduledAt) {
        ObjectNode t = store.lock("tenant", tenantId);
        String status = t.get("status").asString();
        if (!MIGRATABLE.contains(status)) {
            throw ApiException.conflict("invalid_transition", "A " + status + " tenant cannot be migrated");
        }
        UUID currentCell = t.hasNonNull("cell_id") ? UUID.fromString(t.get("cell_id").asString()) : null;
        if (targetCellId.equals(currentCell)) {
            throw ApiException.unprocessable("same_cell", "The tenant is already placed on cell " + targetCellId);
        }
        if (!store.query("select row_to_json(p)::text from tenant_placement p where subject_tenant_id = ? and status = 'MIGRATING'",
                tenantId).isEmpty()) {
            throw ApiException.conflict("migration_in_progress", "A migration of this tenant is already scheduled or running");
        }
        ObjectNode target = store.lock("cell", targetCellId);
        if (t.hasNonNull("data_residency_country") && t.hasNonNull("home_region")
                && !t.get("home_region").asString().equals(target.get("region").asString())) {
            throw ApiException.unprocessable("data_residency_violation", "Tenant data must stay in region "
                    + t.get("home_region").asString() + " (data residency " + t.get("data_residency_country").asString() + ")");
        }
        reserveCapacity(targetCellId);
        OffsetDateTime now = io.meridian.common.time.Clocks.now();
        boolean scheduled = scheduledAt != null && scheduledAt.isAfter(now.plusMinutes(1));
        ObjectNode placement;
        if (scheduled) {
            placement = placement(tenantId, targetCellId, currentCell, "MIGRATING", scheduledAt, "scheduled migration");
        } else {
            store.execute("""
                    update tenant_placement set status = 'COMPLETED', effective_to = now()
                    where subject_tenant_id = ? and status = 'ACTIVE'""", tenantId);
            if (currentCell != null) {
                store.execute("update cell set tenant_count = greatest(tenant_count - 1, 0) where id = ?", currentCell);
            }
            placement = placement(tenantId, targetCellId, currentCell, "ACTIVE", now,
                    currentCell == null ? "initial placement" : "migrated from cell " + currentCell);
            ObjectNode values = json.createObjectNode();
            values.put("cell_id", targetCellId.toString());
            values.put("home_region", target.get("region").asString());
            store.update("tenant", tenantId, values);
        }
        events.publishForTenant(tenantId, "platform.tenant.migrated", "tenant_placement",
                UUID.fromString(placement.get("id").asString()), placement);
        return placement;
    }

    public void requireTenant(UUID id) {
        store.require("tenant", id);
    }

    // ------------------------------------------------------------------ cells

    @Transactional
    public JsonNode drain(UUID cellId, String reason) {
        ObjectNode cell = store.lock("cell", cellId);
        Lifecycle.require(Lifecycle.CELL, "Cell", cell.get("status").asString(), "DRAINING");
        ObjectNode values = json.createObjectNode();
        values.put("status", "DRAINING");
        values.put("drain_reason", reason);
        values.put("drain_requested_at", io.meridian.common.time.Clocks.now().toString());
        ObjectNode row = store.update("cell", cellId, values);
        events.publish("platform.cell.drain_started", "cell", cellId, row);
        return row;
    }

    public void checkCellCreate(Object body) {
        ObjectNode v = crud.toColumns("cell", body);
        String status = v.path("status").asString("PROVISIONING");
        if (!Set.of("PROVISIONING", "ACTIVE").contains(status)) {
            throw ApiException.unprocessable("invalid_initial_status", "A cell starts PROVISIONING or ACTIVE");
        }
        if (v.has("capacity_tenants") && v.get("capacity_tenants").asInt() < 1) {
            throw ApiException.unprocessable("invalid_capacity", "capacity_tenants must be at least 1");
        }
    }

    public void checkCellPatch(UUID id, Object body) {
        ObjectNode v = crud.toColumns("cell", body);
        ObjectNode cell = store.require("cell", id);
        String from = cell.get("status").asString();
        if (v.hasNonNull("status") && !from.equals(v.get("status").asString())) {
            String to = v.get("status").asString();
            if ("DRAINING".equals(to)) {
                throw ApiException.conflict("status_change_requires_action", "Drain a cell with /drain");
            }
            Lifecycle.require(Lifecycle.CELL, "Cell", from, to);
            if ("DECOMMISSIONED".equals(to) && cell.get("tenant_count").asInt() > 0) {
                throw ApiException.unprocessable("cell_not_empty", "Migrate the cell's "
                        + cell.get("tenant_count").asInt() + " tenants before decommissioning it");
            }
        }
        if (v.has("capacity_tenants") && v.get("capacity_tenants").asInt() < Math.max(1, cell.get("tenant_count").asInt())) {
            throw ApiException.unprocessable("invalid_capacity", "capacity_tenants cannot be below the current tenant_count");
        }
    }

    // ------------------------------------------------------------------ helpers

    private ObjectNode reserveCapacity(UUID cellId) {
        ObjectNode cell = store.lock("cell", cellId);
        if (!"ACTIVE".equals(cell.get("status").asString())) {
            throw ApiException.unprocessable("cell_not_accepting_tenants",
                    "Cell " + cell.get("code").asString() + " is " + cell.get("status").asString());
        }
        int count = cell.get("tenant_count").asInt();
        int capacity = cell.get("is_dedicated").asBoolean() ? 1 : cell.get("capacity_tenants").asInt();
        if (count >= capacity) {
            throw ApiException.unprocessable("cell_capacity_exhausted",
                    "Cell " + cell.get("code").asString() + " has no capacity (" + count + "/" + capacity + ")");
        }
        store.execute("update cell set tenant_count = tenant_count + 1 where id = ?", cellId);
        return cell;
    }

    private ObjectNode placement(UUID tenantId, UUID cellId, UUID fromCellId, String status, OffsetDateTime from,
                                 String reason) {
        ObjectNode p = json.createObjectNode();
        p.put("subject_tenant_id", tenantId.toString());
        p.put("cell_id", cellId.toString());
        if (fromCellId != null) {
            p.put("from_cell_id", fromCellId.toString());
        }
        p.put("status", status);
        p.put("effective_from", from.toString());
        p.put("reason", reason);
        return store.insert("tenant_placement", p);
    }

    /**
     * Seeds the tenant-scoped rows of a new tenant: the full module catalogue (enabled per template) and, for a
     * template, its default settings and feature flags. Runs with RLS re-scoped to the new tenant for the rest
     * of the transaction, then restored to the caller's tenant.
     */
    private void seedTenant(UUID tenantId, TenantTemplate template, String currency) {
        UUID caller = TenantContext.require();
        store.scopeTransactionTo(tenantId);
        try {
            TenantContext.callAs(tenantId, TenantContext.principal(), () -> {
                String now = io.meridian.common.time.Clocks.now().toString();
                for (String module : Modules.ALL) {
                    boolean enabled = template != null && template.modules().contains(module);
                    ObjectNode m = json.createObjectNode();
                    m.put("module", module);
                    m.put("is_enabled", enabled);
                    if (enabled) {
                        m.put("enabled_at", now);
                    }
                    store.insert("tenant_module", m);
                }
                if (template != null) {
                    for (Map.Entry<String, TenantTemplate.Setting> e : template.settings().entrySet()) {
                        ObjectNode s = json.createObjectNode();
                        s.put("setting_key", e.getKey());
                        JsonNode value = json.readTree(e.getValue().jsonValue().replace("${currency}", Objects.toString(currency, "USD")));
                        if (SettingValues.isScalarType(e.getValue().valueType())) {
                            ObjectNode wrapped = json.createObjectNode();
                            wrapped.set("value", value);
                            value = wrapped;
                        }
                        s.set("value", value);
                        s.put("value_type", e.getValue().valueType());
                        s.put("category", e.getValue().category());
                        s.put("description", e.getValue().description());
                        store.insert("tenant_setting", s);
                    }
                    for (Map.Entry<String, Boolean> e : template.featureFlags().entrySet()) {
                        ObjectNode f = json.createObjectNode();
                        f.put("flag_key", e.getKey());
                        f.put("is_enabled", e.getValue());
                        f.put("rollout_percentage", e.getValue() ? 100 : 0);
                        f.put("description", "Seeded by template " + template.code());
                        store.insert("feature_flag", f);
                    }
                }
                return null;
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            store.scopeTransactionTo(caller);
        }
    }

    private static void requireCurrency(String c) {
        if (c != null && !Validators.isCurrency(c)) {
            throw ApiException.unprocessable("invalid_currency", "default_currency must be an ISO 4217 code");
        }
    }
}
