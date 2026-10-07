package io.meridian.tenant.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.FourEyes;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.common.time.Clocks;
import io.meridian.tenant.domain.Lifecycle;
import io.meridian.tenant.domain.Placement;
import io.meridian.tenant.infrastructure.TenantStore;
import io.meridian.web.client.ServiceClient;
import io.meridian.web.crud.CrudEngine;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Moves a live tenant to another cell (S20). Steps, each an explicit operator action recorded in {@code history} and
 * published as a {@code platform.tenant_move.*} event:
 *
 * <ol>
 *   <li><b>plan</b> (maker): validates the target (ACTIVE, capacity, data residency, one open move per tenant) and opens
 *       an approval request in workflow-service ({@code tenant_move}, owner tenant-service);</li>
 *   <li><b>approve</b> (checker, in workflow-service): {@link #applyApproved} enforces four-eyes;</li>
 *   <li><b>freeze</b>: the tenant's traffic mode becomes READ_ONLY; the cell router answers its writes with 503 +
 *       Retry-After and keeps serving reads from the source cell;</li>
 *   <li><b>start-copy</b>: hand-off to the data mover ({@code platform.tenant_move.copy_requested} carries source and
 *       target cells and the strategy); the mover replicates the tenant's rows of every service database;</li>
 *   <li><b>confirm-copy</b>: the mover reports a verified copy at a checkpoint (final LSN / checksum digest), no
 *       earlier than {@code freeze-grace} after the freeze, so that no write admitted before the freeze reached every
 *       router (cache TTL + request timeout) can land after the checkpoint;</li>
 *   <li><b>switch</b>: capacity moves to the target, the PRIMARY placement switches ({@code platform.tenant.migrated});
 *       reads follow at once, writes stay frozen;</li>
 *   <li><b>unfreeze</b>: traffic mode NORMAL on the target cell; the move is COMPLETED.</li>
 * </ol>
 * Cancel is possible until the switch and unfreezes the tenant on its source cell.
 */
@Service
public class TenantMoveService {
    public static final String RESOURCE_TYPE = "tenant_move";
    private static final Logger log = LoggerFactory.getLogger(TenantMoveService.class);

    private final TenantStore store;
    private final CrudEngine crud;
    private final Events events;
    private final JsonMapper json;
    private final TenantLifecycleService lifecycle;
    private final TransactionTemplate tx;
    private final Approvals approvals;
    private final Duration freezeGrace;

    public TenantMoveService(TenantStore store, CrudEngine crud, Events events, JsonMapper json,
                             TenantLifecycleService lifecycle, TransactionTemplate tx, ServiceClient client,
                             @Value("${spring.application.name}") String application,
                             @Value("${meridian.tenant.move.freeze-grace:45s}") Duration freezeGrace) {
        this.store = store;
        this.crud = crud;
        this.events = events;
        this.json = json;
        this.lifecycle = lifecycle;
        this.tx = tx;
        this.approvals = new Approvals(client, application, "workflow");
        this.freezeGrace = freezeGrace;
    }

    // ------------------------------------------------------------------ plan (maker)

    /** Validates and records the move, then opens the approval request; nothing changes until a checker approves. */
    public JsonNode plan(Object body, String idempotencyKey) {
        ObjectNode move = tx.execute(s -> insertPlan(body));
        UUID id = UUID.fromString(move.get("id").asString());
        JsonNode request;
        try {
            ObjectNode payload = json.createObjectNode();
            payload.put("move_id", id.toString());
            payload.set("subject_tenant_id", move.get("subject_tenant_id"));
            payload.set("from_cell_id", move.get("from_cell_id"));
            payload.set("to_cell_id", move.get("to_cell_id"));
            payload.set("copy_strategy", move.get("copy_strategy"));
            payload.set("reason", move.get("reason"));
            request = approvals.request(ApprovalSpec.of(RESOURCE_TYPE, id, "move", "Move tenant "
                    + move.path("tenant_code").asString(move.get("subject_tenant_id").asString()) + " to another cell",
                    payload).withSummary(move.get("reason").asString()).withPriority("HIGH"), "move:" + id + ":" + idempotencyKey);
            if (request == null || !request.hasNonNull("id")) {
                throw new ApiException(502, "upstream_error", "workflow-service did not return the created approval request");
            }
        } catch (RuntimeException e) {
            tx.executeWithoutResult(s -> close(id, "CANCELLED", "approval request could not be opened: " + e.getMessage(),
                    "plan_failed"));
            throw e;
        }
        String approvalId = request.get("id").asString();
        return tx.execute(s -> {
            ObjectNode m = store.lock("tenant_move", id);
            ObjectNode values = json.createObjectNode();
            values.put("approval_request_id", approvalId);
            values.set("history", history(m, "approval_requested", "PENDING_APPROVAL", "approval request " + approvalId));
            ObjectNode row = store.update("tenant_move", id, values);
            events.publish("platform.tenant_move.requested", "tenant_move", id, row);
            return row;
        });
    }

    private ObjectNode insertPlan(Object body) {
        ObjectNode values = crud.toColumns("tenant_move", body);
        UUID tenantId = UUID.fromString(values.get("subject_tenant_id").asString());
        UUID toCellId = UUID.fromString(values.get("to_cell_id").asString());
        ObjectNode tenant = store.lock("tenant", tenantId);
        String status = tenant.get("status").asString();
        if (!Placement.MOVABLE_TENANT.contains(status)) {
            throw ApiException.conflict("invalid_transition", "A " + status + " tenant cannot be moved");
        }
        if (!tenant.hasNonNull("cell_id")) {
            throw ApiException.unprocessable("tenant_not_placed", "The tenant has no home cell yet: place it with /migrate");
        }
        if (tenant.hasNonNull("active_move_id")) {
            throw ApiException.conflict("move_in_progress", "Tenant move " + tenant.get("active_move_id").asString()
                    + " is still open");
        }
        ObjectNode from = store.require("cell", UUID.fromString(tenant.get("cell_id").asString()));
        ObjectNode to = store.lock("cell", toCellId);
        Placement.requireMoveTarget(tenant, from, to);
        UUID id = Ids.newId();
        values.put("id", id.toString());
        values.put("from_cell_id", from.get("id").asString());
        values.put("status", "PENDING_APPROVAL");
        ArrayNode h = json.createArrayNode();
        h.add(entry("planned", "PENDING_APPROVAL", "from " + from.get("code").asString() + " to " + to.get("code").asString()));
        values.set("history", h);
        ObjectNode row = store.insert("tenant_move", values);
        ObjectNode t = json.createObjectNode();
        t.put("active_move_id", id.toString());
        store.update("tenant", tenantId, t);
        row.put("tenant_code", tenant.get("code").asString());
        return row;
    }

    // ------------------------------------------------------------------ approval outcome (checker)

    /** Executes an approval: four-eyes, idempotent (a move no longer waiting for approval is left alone). */
    public JsonNode applyApproved(JsonNode approval) {
        if (!RESOURCE_TYPE.equals(approval.path("resource_type").asString())) {
            throw ApiException.unprocessable("wrong_resource_type", "Approval is not for a tenant move");
        }
        if (!"APPROVED".equals(approval.path("status").asString())) {
            throw ApiException.conflict("approval_not_granted", "Approval request is " + approval.path("status").asString());
        }
        String maker = approval.path("maker").asString(approval.path("created_by").asString(null));
        FourEyes.require(maker, "The maker of a tenant move cannot approve it");
        UUID id = UUID.fromString(approval.path("payload").path("move_id").asString(approval.path("resource_id").asString()));
        return tx.execute(s -> {
            ObjectNode m = store.lock("tenant_move", id);
            if (!"PENDING_APPROVAL".equals(m.get("status").asString()) || !sameRequest(m, approval)) {
                return m;
            }
            ObjectNode values = json.createObjectNode();
            values.put("status", "APPROVED");
            values.put("approved_by", TenantContext.principal());
            values.put("approved_at", Clocks.now().toString());
            if (approval.hasNonNull("id")) {
                values.put("approval_request_id", approval.get("id").asString());
            }
            values.set("history", history(m, "approved", "APPROVED", "approval request " + approval.path("id").asString("")));
            ObjectNode row = store.update("tenant_move", id, values);
            events.publish("platform.tenant_move.approved", "tenant_move", id, row);
            return row;
        });
    }

    /** Rejected, expired, changes requested or cancelled in workflow-service: the move ends, the tenant is released. */
    public void applyNotApproved(ApprovalDecision d) {
        UUID id = d.payload() != null && d.payload().hasNonNull("move_id")
                ? UUID.fromString(d.payload().get("move_id").asString()) : d.resourceId();
        if (id == null) {
            return;
        }
        tx.executeWithoutResult(s -> {
            ObjectNode m = store.lock("tenant_move", id);
            if ("PENDING_APPROVAL".equals(m.get("status").asString()) && sameRequest(m, d.request())) {
                close(id, "REJECTED", "approval " + d.outcome().name().toLowerCase(java.util.Locale.ROOT), "rejected");
            }
        });
    }

    private static boolean sameRequest(JsonNode move, JsonNode approval) {
        return approval == null || !move.hasNonNull("approval_request_id") || !approval.hasNonNull("id")
                || move.get("approval_request_id").asString().equals(approval.get("id").asString());
    }

    // ------------------------------------------------------------------ execution steps

    public JsonNode freeze(UUID id) {
        return tx.execute(s -> {
            ObjectNode m = step(id, "FROZEN");
            UUID tenantId = tenantOf(m);
            ObjectNode tenant = store.lock("tenant", tenantId);
            // the target may have filled up or gone out of service since the plan
            Placement.requireMoveTarget(tenant, store.require("cell", UUID.fromString(m.get("from_cell_id").asString())),
                    store.require("cell", UUID.fromString(m.get("to_cell_id").asString())));
            trafficMode(tenantId, "READ_ONLY", "tenant move " + id, id);
            ObjectNode values = json.createObjectNode();
            values.put("status", "FROZEN");
            values.put("frozen_at", Clocks.now().toString());
            values.set("history", history(m, "frozen", "FROZEN", "writes refused at the cell router"));
            return save(id, values, "platform.tenant_move.frozen");
        });
    }

    public JsonNode startCopy(UUID id) {
        return tx.execute(s -> {
            ObjectNode m = step(id, "COPYING");
            ObjectNode values = json.createObjectNode();
            values.put("status", "COPYING");
            values.put("copy_started_at", Clocks.now().toString());
            values.set("history", history(m, "copy_requested", "COPYING", "strategy " + m.get("copy_strategy").asString()));
            ObjectNode row = store.update("tenant_move", id, values);
            // The hand-off: the data mover subscribes to this event (source / target cell endpoints and strategy).
            ObjectNode handoff = row.deepCopy();
            handoff.set("from_cell", cellRef(m.get("from_cell_id")));
            handoff.set("to_cell", cellRef(m.get("to_cell_id")));
            handoff.put("not_before_checkpoint", OffsetDateTime.parse(m.get("frozen_at").asString()).plus(freezeGrace).toString());
            events.publish("platform.tenant_move.copy_requested", "tenant_move", id, handoff);
            return row;
        });
    }

    public JsonNode confirmCopy(UUID id, String checkpoint, boolean verified) {
        return tx.execute(s -> {
            ObjectNode m = step(id, "COPIED");
            if (!verified) {
                throw ApiException.unprocessable("copy_not_verified",
                        "The copy must be verified (row counts and checksums per service database) before the switch");
            }
            OffsetDateTime earliest = OffsetDateTime.parse(m.get("frozen_at").asString()).plus(freezeGrace);
            if (Clocks.now().isBefore(earliest)) {
                throw ApiException.conflict("freeze_grace_pending", "Writes admitted before the freeze may still be in "
                        + "flight; confirm the copy at or after " + earliest);
            }
            ObjectNode values = json.createObjectNode();
            values.put("status", "COPIED");
            values.put("copy_checkpoint", checkpoint);
            values.put("copy_completed_at", Clocks.now().toString());
            values.set("history", history(m, "copy_completed", "COPIED", "checkpoint " + checkpoint));
            return save(id, values, "platform.tenant_move.copy_completed");
        });
    }

    public JsonNode switchPlacement(UUID id) {
        return tx.execute(s -> {
            ObjectNode m = step(id, "SWITCHED");
            UUID tenantId = tenantOf(m);
            UUID from = UUID.fromString(m.get("from_cell_id").asString());
            UUID to = UUID.fromString(m.get("to_cell_id").asString());
            ObjectNode tenant = store.lock("tenant", tenantId);
            ObjectNode target = lifecycle.reserveCapacity(to);
            Placement.requireResidency(tenant, target);
            store.execute("update cell set tenant_count = greatest(tenant_count - 1, 0) where id = ?", from);
            store.execute("""
                    update tenant_placement set status = 'COMPLETED', effective_to = now()
                    where subject_tenant_id = ? and status = 'ACTIVE' and role = 'PRIMARY'""", tenantId);
            ObjectNode placement = lifecycle.placement(tenantId, to, from, "ACTIVE", Clocks.now(),
                    "tenant move " + id + ": " + m.get("reason").asString(), "PRIMARY", id);
            ObjectNode t = json.createObjectNode();
            t.put("cell_id", to.toString());
            t.put("home_region", target.get("region").asString());
            if (to.toString().equals(tenant.path("standby_cell_id").asString(""))) {
                // the old standby is now home: the tenant has no standby until an operator picks one
                t.putNull("standby_cell_id");
                store.execute("""
                        update tenant_placement set status = 'COMPLETED', effective_to = now()
                        where subject_tenant_id = ? and status = 'ACTIVE' and role = 'STANDBY'""", tenantId);
            }
            store.update("tenant", tenantId, t);
            events.publishForTenant(tenantId, "platform.tenant.migrated", "tenant_placement",
                    UUID.fromString(placement.get("id").asString()), placement);
            ObjectNode values = json.createObjectNode();
            values.put("status", "SWITCHED");
            values.put("switched_at", Clocks.now().toString());
            values.put("placement_id", placement.get("id").asString());
            values.set("history", history(m, "switched", "SWITCHED", "home cell is now " + target.get("code").asString()));
            return save(id, values, "platform.tenant_move.switched");
        });
    }

    public JsonNode unfreeze(UUID id) {
        return tx.execute(s -> {
            ObjectNode m = step(id, "COMPLETED");
            UUID tenantId = tenantOf(m);
            store.lock("tenant", tenantId);
            trafficMode(tenantId, "NORMAL", null, null);
            ObjectNode values = json.createObjectNode();
            values.put("status", "COMPLETED");
            values.put("completed_at", Clocks.now().toString());
            values.set("history", history(m, "unfrozen", "COMPLETED", "writes accepted on the target cell"));
            return save(id, values, "platform.tenant_move.completed");
        });
    }

    public JsonNode cancel(UUID id, String reason) {
        JsonNode row = tx.execute(s -> {
            ObjectNode m = step(id, "CANCELLED");
            ObjectNode closed = close(id, "CANCELLED", reason, "cancelled");
            if ("PENDING_APPROVAL".equals(m.get("status").asString())) {
                approvals.cancelOpen(RESOURCE_TYPE, id, "move", "tenant move cancelled: " + reason);
            }
            return closed;
        });
        return row;
    }

    // ------------------------------------------------------------------ helpers

    /** Locks the move and checks the transition to {@code target}. */
    private ObjectNode step(UUID id, String target) {
        ObjectNode m = store.lock("tenant_move", id);
        Lifecycle.require(Lifecycle.TENANT_MOVE, "Tenant move", m.get("status").asString(), target);
        return m;
    }

    /** Ends a move (cancelled / rejected / failed plan): releases the tenant and lifts a freeze this move set. */
    private ObjectNode close(UUID id, String status, String reason, String step) {
        ObjectNode m = store.lock("tenant_move", id);
        UUID tenantId = tenantOf(m);
        ObjectNode tenant = store.lock("tenant", tenantId);
        if (id.toString().equals(tenant.path("active_move_id").asString(""))) {
            if (!"NORMAL".equals(tenant.path("traffic_mode").asString("NORMAL"))) {
                trafficMode(tenantId, "NORMAL", null, null);
            } else {
                ObjectNode t = json.createObjectNode();
                t.putNull("active_move_id");
                store.update("tenant", tenantId, t);
            }
        }
        ObjectNode values = json.createObjectNode();
        values.put("status", status);
        values.put("cancelled_at", Clocks.now().toString());
        values.put("cancel_reason", reason.length() > 255 ? reason.substring(0, 255) : reason);
        values.set("history", history(m, step, status, reason));
        return save(id, values, "REJECTED".equals(status) ? "platform.tenant_move.rejected" : "platform.tenant_move.cancelled");
    }

    /** Sets the tenant's traffic mode (cell router) and publishes {@code platform.tenant.traffic_mode_changed}. */
    private void trafficMode(UUID tenantId, String mode, String reason, UUID moveId) {
        ObjectNode t = json.createObjectNode();
        t.put("traffic_mode", mode);
        if (reason == null) {
            t.putNull("traffic_mode_reason");
        } else {
            t.put("traffic_mode_reason", reason);
        }
        if (moveId == null) {
            t.putNull("active_move_id");
        } else {
            t.put("active_move_id", moveId.toString());
        }
        ObjectNode row = store.update("tenant", tenantId, t);
        events.publishForTenant(tenantId, "platform.tenant.traffic_mode_changed", "tenant", tenantId, row);
    }

    private ObjectNode save(UUID id, ObjectNode values, String event) {
        ObjectNode row = store.update("tenant_move", id, values);
        events.publish(event, "tenant_move", id, row);
        log.info("Tenant move {} -> {} by {}", id, row.get("status").asString(), TenantContext.principal());
        return row;
    }

    private ArrayNode history(ObjectNode move, String step, String status, String detail) {
        ArrayNode h = move.get("history") instanceof ArrayNode a ? a.deepCopy() : json.createArrayNode();
        h.add(entry(step, status, detail));
        return h;
    }

    private ObjectNode entry(String step, String status, String detail) {
        ObjectNode e = json.createObjectNode();
        e.put("step", step);
        e.put("status", status);
        e.put("at", Clocks.now().toString());
        e.put("by", TenantContext.principal());
        if (detail != null) {
            e.put("detail", detail);
        }
        return e;
    }

    private ObjectNode cellRef(JsonNode cellId) {
        ObjectNode cell = store.require("cell", UUID.fromString(cellId.asString()));
        ObjectNode c = json.createObjectNode();
        // endpoints: the data mover reads services_url_template / s3_endpoint / s3_bucket / s3_region from it
        // (credentials never: those live in the mover's own configuration)
        for (String f : new String[] {"id", "code", "region", "gateway_url", "ledger_db_cluster", "kafka_cluster",
                "endpoints"}) {
            if (cell.hasNonNull(f)) {
                c.set(f, cell.get(f));
            }
        }
        return c;
    }

    private static UUID tenantOf(JsonNode move) {
        return UUID.fromString(move.get("subject_tenant_id").asString());
    }
}
