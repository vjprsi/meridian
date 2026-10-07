package io.meridian.businesschannel.application;

import static io.meridian.businesschannel.application.CurrentUser.business;
import static io.meridian.businesschannel.application.CurrentUser.userId;

import io.meridian.businesschannel.application.Access.Need;
import io.meridian.common.error.ApiException;
import io.meridian.common.time.Clocks;
import io.meridian.events.OutboxWriter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The business's portal users and their account entitlements, managed by the business's ADMIN users (step-up).
 * Guard rails: an admin cannot change their own role, suspend or remove themselves (so a business always keeps an
 * admin), the last active admin cannot be demoted, initiate entitlements need a MAKER or ADMIN, approve entitlements an
 * APPROVER or ADMIN, and entitlements only ever name accounts of the business.
 */
@Service
public class UserAdminService {
    static final String USERS = "business_user";
    static final String ENTITLEMENTS = "business_entitlement";
    static final Set<String> ROLES = Set.of("ADMIN", "MAKER", "APPROVER", "VIEWER");

    private final Rows rows;
    private final Access access;
    private final StepUp stepUp;
    private final ActionAudit audit;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final CurrentUser current;
    private final Downstream down;

    public UserAdminService(Rows rows, Access access, StepUp stepUp, ActionAudit audit, OutboxWriter outbox,
                            TransactionTemplate tx, CurrentUser current, Downstream down) {
        this.rows = rows;
        this.access = access;
        this.stepUp = stepUp;
        this.audit = audit;
        this.outbox = outbox;
        this.tx = tx;
        this.current = current;
        this.down = down;
    }

    // ------------------------------------------------------------------ me

    /** The signed-in user, the business and the user's entitlements; records the sign-in time. */
    public JsonNode me() {
        ObjectNode user = rows.update(USERS, userId(), rows.object().put("last_login_at", Clocks.now().toString()));
        ObjectNode out = rows.object();
        out.set("user", user);
        JsonNode customer = down.get("customer-service", "/v1/customers/" + business());
        ObjectNode biz = rows.object();
        for (String f : List.of("id", "customer_number", "customer_type", "display_name", "legal_name", "trading_name",
                "registration_number", "status")) {
            if (customer.has(f)) {
                biz.set(f, customer.get(f));
            }
        }
        out.set("business", biz);
        ArrayNode ents = out.putArray("entitlements");
        entitlementRows(userId()).forEach(ents::add);
        return out;
    }

    // ------------------------------------------------------------------ users

    public JsonNode list(String status, Integer limit, String cursor) {
        int n = Rows.limit(limit);
        UUID after = Rows.cursor(cursor);
        return rows.page(rows.list("select row_to_json(t)::text from business_user t where t.business_customer_id = ? "
                        + "and (?::text is null or t.status = ?::text) and (?::uuid is null or t.id < ?::uuid) "
                        + "order by t.id desc limit ?", business(), status, status, after, after, n + 1), n);
    }

    public JsonNode get(UUID id) {
        return user(id, "businessUsersGet");
    }

    public JsonNode create(JsonNode req) {
        String action = "businessUsersCreate";
        access.requireAdmin(action, "USER", null);
        String role = role(Req.required(req, "role"));
        String name = Req.required(req, "display_name");
        String email = Req.required(req, "email").toLowerCase();
        ObjectNode u = rows.object()
                .put("business_customer_id", business().toString())
                .put("display_name", name)
                .put("email", email)
                .put("role", role)
                .put("status", "ACTIVE");
        limit(req, u);
        stepUp.require(action, "USER", null);
        ObjectNode saved = tx.execute(s -> {
            ObjectNode r = rows.insert(USERS, u);
            changed("ADDED", r);
            return r;
        });
        audit.succeeded(action, "USER", saved.path("id").asString(), saved.path("id").asString());
        return saved;
    }

    public JsonNode update(UUID id, JsonNode req) {
        String action = "businessUsersUpdate";
        access.requireAdmin(action, "USER", id);
        ObjectNode existing = user(id, action);
        ObjectNode change = rows.object();
        String role = Req.text(req, "role");
        if (role != null && !role.equals(existing.path("role").asString())) {
            role(role);
            if ("TECHNICAL".equals(existing.path("user_type").asString("HUMAN"))
                    && !IntegrationService.TECHNICAL_ROLES.contains(role)) {
                throw ApiException.unprocessable("technical_role_not_allowed",
                        "An API client's technical user is a MAKER or a VIEWER (it never approves or administers)");
            }
            if (id.equals(userId())) {
                throw ApiException.unprocessable("own_role_change_forbidden", "Administrators cannot change their own role");
            }
            change.put("role", role);
        }
        String name = Req.text(req, "display_name");
        if (name != null && !name.isBlank()) {
            change.put("display_name", name.trim());
        }
        if (req.has("daily_limit") || req.has("limit_currency")) {
            limit(req, change);
        }
        stepUp.require(action, "USER", id);
        ObjectNode saved = tx.execute(s -> {
            rows.lockKey("business-users:" + business());
            if (change.has("role") && "ADMIN".equals(existing.path("role").asString())) {
                requireAnotherAdmin(id);
            }
            if (change.has("role")) {
                dropIncompatibleEntitlements(id, change.get("role").asString());
            }
            ObjectNode r = change.isEmpty() ? existing : rows.update(USERS, id, change);
            changed("CHANGED", r);
            return r;
        });
        audit.succeeded(action, "USER", id, null);
        return saved;
    }

    public JsonNode status(UUID id, String op, JsonNode req) {
        String action = switch (op) {
            case "suspend" -> "businessUsersSuspend";
            case "reactivate" -> "businessUsersReactivate";
            default -> "businessUsersRemove";
        };
        access.requireAdmin(action, "USER", id);
        ObjectNode existing = user(id, action);
        if (id.equals(userId()) && !op.equals("reactivate")) {
            throw ApiException.unprocessable("own_status_change_forbidden", "Administrators cannot " + op + " themselves");
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
            rows.lockKey("business-users:" + business());
            if ("ADMIN".equals(existing.path("role").asString()) && !"ACTIVE".equals(to)) {
                requireAnotherAdmin(id);
            }
            if ("REMOVED".equals(to)) {
                rows.execute("delete from business_entitlement where business_customer_id = ? and business_user_id = ?",
                        business(), id);
            }
            ObjectNode r = rows.update(USERS, id, rows.object().put("status", to).put("status_reason", reason));
            changed(to, r);
            return r;
        });
        audit.succeeded(action, "USER", id, null);
        return saved;
    }

    // ------------------------------------------------------------------ entitlements

    public JsonNode entitlements(UUID id) {
        ObjectNode u = user(id, "businessUsersEntitlements");
        if (!CurrentUser.admin() && !id.equals(userId())) {
            throw access.notEntitled("businessUsersEntitlements", "USER", id,
                    "Only administrators see other users' entitlements");
        }
        return rows.page(entitlementRows(UUID.fromString(u.path("id").asString())));
    }

    public JsonNode setEntitlement(UUID id, UUID accountId, JsonNode req) {
        String action = "businessUsersReplaceEntitlements";
        access.requireAdmin(action, "USER", id);
        ObjectNode u = user(id, action);
        access.businessAccount(accountId, action);
        String role = u.path("role").asString();
        boolean view = Req.bool(req, "can_view", true);
        boolean initiate = Req.bool(req, "can_initiate", false);
        boolean approve = Req.bool(req, "can_approve", false);
        if (initiate && !Set.of("ADMIN", "MAKER").contains(role)) {
            throw ApiException.unprocessable("role_cannot_initiate", "Only MAKER or ADMIN users can be entitled to initiate");
        }
        if (approve && !Set.of("ADMIN", "APPROVER").contains(role)) {
            throw ApiException.unprocessable("role_cannot_approve", "Only APPROVER or ADMIN users can be entitled to approve");
        }
        if ((initiate || approve) && !view) {
            throw ApiException.unprocessable("view_required", "Initiate and approve entitlements need can_view");
        }
        BigDecimal max = Req.decimal(req, "max_amount");
        if (max != null && max.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount", "max_amount must be greater than zero");
        }
        if ("REMOVED".equals(u.path("status").asString())) {
            throw ApiException.conflict("user_removed", "The user was removed");
        }
        stepUp.require(action, "USER", id);
        ObjectNode saved = tx.execute(s -> {
            ObjectNode values = rows.object().put("can_view", view).put("can_initiate", initiate)
                    .put("can_approve", approve).put("max_amount", max == null ? null : max.toPlainString());
            ObjectNode r = access.entitlement(id, accountId)
                    .map(e -> rows.update(ENTITLEMENTS, UUID.fromString(e.path("id").asString()), values))
                    .orElseGet(() -> rows.insert(ENTITLEMENTS, values.put("business_customer_id", business().toString())
                            .put("business_user_id", id.toString()).put("deposit_account_id", accountId.toString())));
            outbox.publish("channels.business_entitlement.changed", ENTITLEMENTS, UUID.fromString(r.path("id").asString()),
                    rows.object().put("change", "SET").set("entitlement", r));
            return r;
        });
        audit.succeeded(action, "USER", id, saved.path("id").asString());
        return saved;
    }

    public void removeEntitlement(UUID id, UUID accountId) {
        String action = "businessUsersRemoveEntitlements";
        access.requireAdmin(action, "USER", id);
        user(id, action);
        ObjectNode e = access.entitlement(id, accountId).orElseThrow(() -> Access.notFound("ENTITLEMENT", accountId));
        stepUp.require(action, "USER", id);
        tx.executeWithoutResult(s -> {
            rows.execute("delete from business_entitlement where id = ? and business_customer_id = ?",
                    UUID.fromString(e.path("id").asString()), business());
            outbox.publish("channels.business_entitlement.changed", ENTITLEMENTS, UUID.fromString(e.path("id").asString()),
                    rows.object().put("change", "REMOVED").set("entitlement", e));
        });
        audit.succeeded(action, "USER", id, null);
    }

    // ------------------------------------------------------------------ helpers

    /** A user of the business (any status); another business's user: 404, audited. */
    public ObjectNode user(UUID id, String action) {
        return rows.find(USERS, business(), id).orElseThrow(() -> access.deny(action, "USER", id));
    }

    List<ObjectNode> entitlementRows(UUID user) {
        return rows.list("select row_to_json(t)::text from business_entitlement t where t.business_customer_id = ? "
                + "and t.business_user_id = ? order by t.created_at", business(), user);
    }

    /** Whether a user can still act on an account (used by the approval matrix: designated approvers). */
    boolean canApprove(ObjectNode user, UUID accountId) {
        return "ACTIVE".equals(user.path("status").asString()) && access.entitled(user, accountId, Need.APPROVE);
    }

    private static String role(String role) {
        if (!ROLES.contains(role)) {
            throw ApiException.unprocessable("invalid_role", "role must be one of " + ROLES);
        }
        return role;
    }

    private static void limit(JsonNode req, ObjectNode out) {
        BigDecimal limit = Req.decimal(req, "daily_limit");
        String ccy = Req.text(req, "limit_currency");
        if (limit != null && (ccy == null || ccy.isBlank())) {
            throw ApiException.unprocessable("limit_currency_required", "daily_limit needs limit_currency");
        }
        if (limit != null && limit.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount", "daily_limit must be greater than zero");
        }
        out.put("daily_limit", limit == null ? null : limit.toPlainString());
        out.put("limit_currency", limit == null ? null : ccy.toUpperCase());
    }

    private void requireAnotherAdmin(UUID except) {
        long admins = rows.scalar("select count(*) from business_user where business_customer_id = ? and role = 'ADMIN' "
                + "and status = 'ACTIVE' and id <> ?", Long.class, business(), except).orElse(0L);
        if (admins == 0) {
            throw ApiException.unprocessable("last_admin", "The business must keep at least one active administrator");
        }
    }

    /** A role change takes away entitlements the new role cannot hold. */
    private void dropIncompatibleEntitlements(UUID id, String role) {
        if (!Set.of("ADMIN", "MAKER").contains(role)) {
            rows.execute("update business_entitlement set can_initiate = false where business_customer_id = ? "
                    + "and business_user_id = ? and can_initiate", business(), id);
        }
        if (!Set.of("ADMIN", "APPROVER").contains(role)) {
            rows.execute("update business_entitlement set can_approve = false where business_customer_id = ? "
                    + "and business_user_id = ? and can_approve", business(), id);
        }
    }

    private void changed(String change, ObjectNode user) {
        outbox.publish("channels.business_user.changed", USERS, UUID.fromString(user.path("id").asString()),
                rows.object().put("change", change).set("user", user));
        if (UUID.fromString(user.path("id").asString()).equals(userId())) {
            current.refresh();
        }
    }

    static List<UUID> uuids(JsonNode arr) {
        List<UUID> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            arr.forEach(x -> out.add(UUID.fromString(x.asString())));
        }
        return out;
    }
}
