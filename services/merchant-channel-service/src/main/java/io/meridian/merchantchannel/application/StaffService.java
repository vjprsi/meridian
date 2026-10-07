package io.meridian.merchantchannel.application;

import static io.meridian.merchantchannel.application.CurrentUser.merchant;
import static io.meridian.merchantchannel.application.CurrentUser.userId;

import io.meridian.common.error.ApiException;
import io.meridian.events.OutboxWriter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The merchant's app users, their roles and store scopes, managed by the merchant's OWNER users (step-up). Guard rails:
 * an owner cannot change their own role, suspend or remove themselves, the last active owner cannot be demoted, and
 * store assignments only ever name stores of the merchant. OWNER and MANAGER users see the staff list; everyone sees
 * their own record and stores.
 */
@Service
public class StaffService {
    static final String USERS = CurrentUser.USERS;
    static final String STORES = "merchant_user_store";
    static final Set<String> ROLES = Set.of("OWNER", "MANAGER", "CASHIER", "VIEWER");

    private final Rows rows;
    private final Access access;
    private final StepUp stepUp;
    private final ActionAudit audit;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final CurrentUser current;

    public StaffService(Rows rows, Access access, StepUp stepUp, ActionAudit audit, OutboxWriter outbox,
                        TransactionTemplate tx, CurrentUser current) {
        this.rows = rows;
        this.access = access;
        this.stepUp = stepUp;
        this.audit = audit;
        this.outbox = outbox;
        this.tx = tx;
        this.current = current;
    }

    public JsonNode list(String status, Integer limit, String cursor) {
        access.requireRole("merchantAppStaffList", "USER", null, "OWNER", "MANAGER");
        int n = Rows.limit(limit);
        UUID after = Rows.cursor(cursor);
        return rows.page(rows.list("select row_to_json(t)::text from merchant_user t where t.merchant_id = ? "
                        + "and (?::text is null or t.status = ?::text) and (?::uuid is null or t.id < ?::uuid) "
                        + "order by t.id desc limit ?", merchant(), status, status, after, after, n + 1), n);
    }

    public JsonNode get(UUID id) {
        String action = "merchantAppStaffGet";
        ObjectNode u = user(id, action);
        if (!id.equals(userId())) {
            access.requireRole(action, "USER", id, "OWNER", "MANAGER");
        }
        return u;
    }

    public JsonNode stores(UUID id) {
        String action = "merchantAppStaffStores";
        user(id, action);
        if (!id.equals(userId())) {
            access.requireRole(action, "USER", id, "OWNER", "MANAGER");
        }
        return rows.page(storeRows(id));
    }

    public JsonNode create(JsonNode req) {
        String action = "merchantAppStaffCreate";
        access.requireOwner(action, "USER", null);
        String role = role(Req.required(req, "role"));
        String name = Req.required(req, "display_name");
        String email = Req.required(req, "email").toLowerCase();
        boolean all = "OWNER".equals(role) || Req.bool(req, "all_stores", false);
        Set<UUID> stores = storeIds(req, action);
        stepUp.require(action, "USER", null);
        ObjectNode saved = tx.execute(s -> {
            ObjectNode r = rows.insert(USERS, rows.object()
                    .put("merchant_id", merchant().toString())
                    .put("display_name", name.length() > 160 ? name.substring(0, 160) : name)
                    .put("email", email)
                    .put("role", role)
                    .put("all_stores", all)
                    .put("status", "ACTIVE"));
            assign(UUID.fromString(r.path("id").asString()), stores);
            changed("ADDED", r);
            return r;
        });
        audit.succeeded(action, "USER", saved.path("id").asString(), saved.path("id").asString());
        return saved;
    }

    public JsonNode update(UUID id, JsonNode req) {
        String action = "merchantAppStaffUpdate";
        access.requireOwner(action, "USER", id);
        ObjectNode existing = user(id, action);
        ObjectNode change = rows.object();
        String role = Req.text(req, "role");
        if (role != null && !role.equals(existing.path("role").asString())) {
            role(role);
            if (id.equals(userId())) {
                throw ApiException.unprocessable("own_role_change_forbidden", "Owners cannot change their own role");
            }
            change.put("role", role);
            if ("OWNER".equals(role)) {
                change.put("all_stores", true);
            }
        }
        String name = Req.text(req, "display_name");
        if (name != null && !name.isBlank()) {
            change.put("display_name", name.trim());
        }
        if (req.hasNonNull("all_stores") && !change.has("all_stores")) {
            String effective = change.has("role") ? change.get("role").asString() : existing.path("role").asString();
            change.put("all_stores", "OWNER".equals(effective) || Req.bool(req, "all_stores", false));
        }
        stepUp.require(action, "USER", id);
        ObjectNode saved = tx.execute(s -> {
            rows.lockKey("merchant-users:" + merchant());
            if (change.has("role") && "OWNER".equals(existing.path("role").asString())) {
                requireAnotherOwner(id);
            }
            ObjectNode r = change.isEmpty() ? existing : rows.update(USERS, id, change);
            changed("CHANGED", r);
            return r;
        });
        audit.succeeded(action, "USER", id, null);
        return saved;
    }

    public JsonNode replaceStores(UUID id, JsonNode req) {
        String action = "merchantAppStaffReplaceStores";
        access.requireOwner(action, "USER", id);
        ObjectNode u = user(id, action);
        if ("REMOVED".equals(u.path("status").asString())) {
            throw ApiException.conflict("user_removed", "The user was removed");
        }
        if (!req.has("store_ids") || !req.get("store_ids").isArray()) {
            throw ApiException.unprocessable("missing_field", "store_ids is required");
        }
        Set<UUID> stores = storeIds(req, action);
        stepUp.require(action, "USER", id);
        tx.executeWithoutResult(s -> {
            rows.execute("delete from merchant_user_store where merchant_id = ? and merchant_user_id = ?", merchant(), id);
            assign(id, stores);
            changed("STORES_CHANGED", u);
        });
        audit.succeeded(action, "USER", id, null);
        return rows.page(storeRows(id));
    }

    public JsonNode status(UUID id, String op, JsonNode req) {
        String action = switch (op) {
            case "suspend" -> "merchantAppStaffSuspend";
            case "reactivate" -> "merchantAppStaffReactivate";
            default -> "merchantAppStaffRemove";
        };
        access.requireOwner(action, "USER", id);
        ObjectNode existing = user(id, action);
        if (id.equals(userId()) && !op.equals("reactivate")) {
            throw ApiException.unprocessable("own_status_change_forbidden", "Owners cannot " + op + " themselves");
        }
        String from = existing.path("status").asString();
        String to = switch (op) {
            case "suspend" -> {
                if (!"ACTIVE".equals(from)) {
                    throw ApiException.conflict("invalid_transition", "Only an ACTIVE user can be suspended");
                }
                yield "SUSPENDED";
            }
            case "reactivate" -> {
                if (!"SUSPENDED".equals(from)) {
                    throw ApiException.conflict("invalid_transition", "Only a SUSPENDED user can be reactivated");
                }
                yield "ACTIVE";
            }
            default -> {
                if ("REMOVED".equals(from)) {
                    throw ApiException.conflict("invalid_transition", "The user is already removed");
                }
                yield "REMOVED";
            }
        };
        stepUp.require(action, "USER", id);
        String reason = Req.text(req, "reason");
        ObjectNode saved = tx.execute(s -> {
            rows.lockKey("merchant-users:" + merchant());
            if ("OWNER".equals(existing.path("role").asString()) && !"ACTIVE".equals(to)) {
                requireAnotherOwner(id);
            }
            if ("REMOVED".equals(to)) {
                rows.execute("delete from merchant_user_store where merchant_id = ? and merchant_user_id = ?",
                        merchant(), id);
            }
            ObjectNode r = rows.update(USERS, id, rows.object().put("status", to).put("status_reason", reason));
            changed(to, r);
            return r;
        });
        audit.succeeded(action, "USER", id, null);
        return saved;
    }

    // ------------------------------------------------------------------ helpers

    /** A user of the merchant (any status); another merchant's user: 404, audited. */
    public ObjectNode user(UUID id, String action) {
        return rows.find(USERS, merchant(), id).orElseThrow(() -> access.deny(action, "USER", id));
    }

    List<ObjectNode> storeRows(UUID user) {
        return rows.list("select row_to_json(t)::text from merchant_user_store t where t.merchant_id = ? "
                + "and t.merchant_user_id = ? order by t.created_at", merchant(), user);
    }

    /** The store ids of a request, each a store of the merchant (else 404, audited). */
    private Set<UUID> storeIds(JsonNode req, String action) {
        Set<UUID> ids = new LinkedHashSet<>();
        JsonNode arr = req.get("store_ids");
        if (arr != null && arr.isArray()) {
            for (JsonNode x : arr) {
                UUID id;
                try {
                    id = UUID.fromString(x.asString());
                } catch (IllegalArgumentException e) {
                    throw ApiException.badRequest("invalid_value", "store_ids must be UUIDs");
                }
                ids.add(id);
            }
        }
        if (ids.size() > 200) {
            throw ApiException.unprocessable("too_many_stores", "At most 200 stores per user");
        }
        for (UUID id : ids) {
            access.merchantStore(id, action);
        }
        return ids;
    }

    private void assign(UUID user, Set<UUID> stores) {
        for (UUID s : stores) {
            rows.insert(STORES, rows.object().put("merchant_id", merchant().toString())
                    .put("merchant_user_id", user.toString()).put("merchant_store_id", s.toString()));
        }
    }

    private static String role(String role) {
        if (!ROLES.contains(role)) {
            throw ApiException.unprocessable("invalid_role", "role must be one of " + ROLES);
        }
        return role;
    }

    private void requireAnotherOwner(UUID except) {
        long owners = rows.scalar("select count(*) from merchant_user where merchant_id = ? and role = 'OWNER' "
                + "and status = 'ACTIVE' and id <> ?", Long.class, merchant(), except).orElse(0L);
        if (owners == 0) {
            throw ApiException.unprocessable("last_owner", "The merchant must keep at least one active owner");
        }
    }

    private void changed(String change, ObjectNode user) {
        outbox.publish("channels.merchant_user.changed", USERS, UUID.fromString(user.path("id").asString()),
                rows.object().put("change", change).set("user", user));
        if (UUID.fromString(user.path("id").asString()).equals(userId())) {
            current.refresh();
        }
    }
}
