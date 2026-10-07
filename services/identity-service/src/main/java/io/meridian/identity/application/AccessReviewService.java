package io.meridian.identity.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.common.time.Clocks;
import io.meridian.events.OutboxWriter;
import io.meridian.identity.infrastructure.IdentityStore;
import io.meridian.web.crud.CrudEngine;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * GAP-13: periodic access recertification.
 *
 * <ul>
 *   <li>{@link #report}: users x roles x last sign-in, privileged role holders and dormant accounts (no names, e-mail or
 *       phone: user ids, usernames and role codes only).</li>
 *   <li>{@link #open}: one review per period (e.g. {@code 2026-Q4}); every active role assignment of a non-deactivated
 *       user is snapshotted as an item ({@code access_review_item}). {@link AccessReviewScheduler} opens the quarter's
 *       review on the first day of each quarter.</li>
 *   <li>{@link #decide}: the reviewer attests or revokes each item (a revocation needs a comment; nobody reviews their
 *       own assignments).</li>
 *   <li>{@link #submit}: once every item is decided, the decisions go to a second person through central approvals
 *       (resource type {@code access_review}, action {@code complete}, recorded as a {@code privileged_change}).</li>
 *   <li>{@link #complete}: on approval ({@link ApprovalExecutor}, four-eyes re-checked there) the revoked assignments are
 *       removed ({@code platform.user_role.revoked}) and the review is COMPLETED; a rejection returns it to OPEN.</li>
 * </ul>
 */
@Service
public class AccessReviewService {
    public static final String RESOURCE_TYPE = "access_review";
    public static final String ACTION = "complete";
    private static final Set<String> DECISIONS = Set.of("ATTEST", "REVOKE");

    private final IdentityStore store;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final IdentityApprovals workflow;
    private final UserRoleService userRoles;
    private final JsonMapper json;

    public AccessReviewService(IdentityStore store, CrudEngine crud, OutboxWriter outbox, IdentityApprovals workflow,
                               UserRoleService userRoles, JsonMapper json) {
        this.store = store;
        this.crud = crud;
        this.outbox = outbox;
        this.workflow = workflow;
        this.userRoles = userRoles;
        this.json = json;
    }

    // ------------------------------------------------------------------ report

    /** The access review report of the current tenant ({@code dormantDays} default 90). */
    public ObjectNode report(Integer dormantDays) {
        TenantContext.require();
        int days = dormantDays == null ? 90 : dormantDays;
        if (days < 1 || days > 3650) {
            throw ApiException.unprocessable("invalid_dormant_days", "dormant_days must be between 1 and 3650");
        }
        List<String> rows = store.rows("""
                select json_build_object('user_id', u.id, 'username', u.username, 'user_type', u.user_type,
                       'status', u.status, 'last_login_at', u.last_login_at, 'mfa_enrolled', u.mfa_enrolled,
                       'dormant', coalesce(u.last_login_at, u.invited_at, u.created_at) < now() - make_interval(days => ?),
                       'user_role_id', ur.id, 'role_id', r.id, 'role_code', r.code, 'is_privileged', r.is_privileged,
                       'scope_type', ur.scope_type, 'valid_to', ur.valid_to,
                       'approval_request_id', ur.approval_request_id)::text
                from app_user u
                left join user_role ur on ur.user_id = u.id and (ur.valid_to is null or ur.valid_to > now())
                left join role r on r.id = ur.role_id
                where u.status <> 'DEACTIVATED'
                order by u.username, r.code""", days);
        Map<String, ObjectNode> users = new LinkedHashMap<>();
        ArrayNode privileged = json.createArrayNode();
        int assignments = 0;
        for (String row : rows) {
            JsonNode r = json.readTree(row);
            ObjectNode user = users.computeIfAbsent(r.get("user_id").asString(), id -> {
                ObjectNode u = json.createObjectNode();
                for (String f : List.of("user_id", "username", "user_type", "status", "last_login_at", "mfa_enrolled",
                        "dormant")) {
                    u.set(f, r.get(f));
                }
                u.putArray("roles");
                return u;
            });
            if (r.hasNonNull("role_id")) {
                assignments++;
                ObjectNode role = ((ArrayNode) user.get("roles")).addObject();
                for (String f : List.of("user_role_id", "role_id", "role_code", "is_privileged", "scope_type", "valid_to")) {
                    role.set(f, r.get(f));
                }
                if (r.path("is_privileged").asBoolean(false)) {
                    ObjectNode p = privileged.addObject();
                    for (String f : List.of("user_id", "username", "role_code", "valid_to", "approval_request_id",
                            "last_login_at")) {
                        p.set(f, r.get(f));
                    }
                    p.put("standing", !r.hasNonNull("valid_to"));
                }
            }
        }
        ArrayNode dormant = json.createArrayNode();
        ArrayNode noMfa = json.createArrayNode();
        ArrayNode list = json.createArrayNode();
        for (ObjectNode u : users.values()) {
            list.add(u);
            if (u.path("dormant").asBoolean(false)) {
                ObjectNode d = dormant.addObject();
                d.set("user_id", u.get("user_id"));
                d.set("username", u.get("username"));
                d.set("status", u.get("status"));
                d.set("last_login_at", u.get("last_login_at"));
                d.put("role_count", u.get("roles").size());
            }
            if (!u.path("mfa_enrolled").asBoolean(false)) {
                noMfa.add(u.get("user_id"));
            }
        }
        ObjectNode out = json.createObjectNode();
        out.put("generated_at", Clocks.now().toString());
        out.put("dormant_days", days);
        ObjectNode totals = out.putObject("totals");
        totals.put("users", users.size());
        totals.put("assignments", assignments);
        totals.put("privileged_assignments", privileged.size());
        totals.put("dormant_accounts", dormant.size());
        totals.put("users_without_mfa", noMfa.size());
        out.set("users", list);
        out.set("privileged_role_holders", privileged);
        out.set("dormant_accounts", dormant);
        out.set("users_without_mfa", noMfa);
        return out;
    }

    // ------------------------------------------------------------------ lifecycle

    /** Quarter label of a date: {@code 2026-Q4}. */
    public static String quarterOf(LocalDate day) {
        return day.getYear() + "-Q" + ((day.getMonthValue() - 1) / 3 + 1);
    }

    /** Opens a review and snapshots the active assignments (body: period, due_date, dormant_days). */
    @Transactional
    public String open(Object body) {
        ObjectNode v = crud.toColumns("access_review", body);
        String period = v.path("period").asString("").trim();
        if (!period.matches("^\\d{4}-(Q[1-4]|H[12]|M(0[1-9]|1[0-2])|[A-Z0-9-]{1,8})$")) {
            throw ApiException.unprocessable("invalid_period", "period must look like 2026-Q4 (or 2026-H2, 2026-M10)");
        }
        int days = v.hasNonNull("dormant_days") ? v.get("dormant_days").asInt() : 90;
        if (days < 1 || days > 3650) {
            throw ApiException.unprocessable("invalid_dormant_days", "dormant_days must be between 1 and 3650");
        }
        if (crud.findOne("access_review", Map.of("period", period)).isPresent()) {
            throw ApiException.conflict("duplicate_period", "An access review for " + period + " already exists");
        }
        v.put("period", period);
        v.put("dormant_days", days);
        v.put("status", "OPEN");
        String review = crud.insert("access_review", v);
        UUID reviewId = UUID.fromString(json.readTree(review).get("id").asString());
        List<String> assignments = store.rows("""
                select json_build_object('user_role_id', ur.id, 'user_id', ur.user_id, 'role_id', ur.role_id,
                       'role_code', r.code, 'is_privileged', r.is_privileged, 'user_status', u.status,
                       'last_login_at', u.last_login_at, 'valid_to', ur.valid_to,
                       'dormant', coalesce(u.last_login_at, u.invited_at, u.created_at) < now() - make_interval(days => ?))::text
                from user_role ur join app_user u on u.id = ur.user_id join role r on r.id = ur.role_id
                where (ur.valid_to is null or ur.valid_to > now()) and u.status <> 'DEACTIVATED'
                order by u.username, r.code""", days);
        List<ObjectNode> items = new ArrayList<>();
        int privileged = 0;
        int dormant = 0;
        for (String a : assignments) {
            ObjectNode item = (ObjectNode) json.readTree(a);
            item.put("access_review_id", reviewId.toString());
            item.put("decision", "PENDING");
            privileged += item.path("is_privileged").asBoolean(false) ? 1 : 0;
            dormant += item.path("dormant").asBoolean(false) ? 1 : 0;
            items.add(item);
        }
        if (!items.isEmpty()) {
            crud.insertMany("access_review_item", items);
        }
        String row = store.updateReturning("""
                update access_review t set item_count = ?, privileged_count = ?, dormant_count = ?, updated_by = ?
                where id = ? returning row_to_json(t)::text""",
                items.size(), privileged, dormant, TenantContext.principal(), reviewId);
        outbox.publish("platform.access_review.opened", "access_review", reviewId, json.readTree(row));
        return row;
    }

    /** Records attest / revoke decisions on items of an OPEN review. */
    @Transactional
    public String decide(UUID reviewId, JsonNode decisions) {
        JsonNode review = store.lock("access_review", reviewId);
        requireStatus(review, "OPEN");
        if (decisions == null || !decisions.isArray() || decisions.isEmpty()) {
            throw ApiException.unprocessable("decisions_required", "decisions must list {item_id, decision, comment}");
        }
        String principal = TenantContext.principal();
        Map<String, JsonNode> users = new HashMap<>();
        OffsetDateTime now = Clocks.now();
        for (JsonNode d : decisions) {
            String decision = d.path("decision").asString("").toUpperCase();
            if (!DECISIONS.contains(decision)) {
                throw ApiException.unprocessable("invalid_decision", "decision must be ATTEST or REVOKE");
            }
            String comment = d.hasNonNull("comment") ? d.get("comment").asString().trim() : null;
            if ("REVOKE".equals(decision) && (comment == null || comment.isEmpty())) {
                throw ApiException.unprocessable("comment_required", "A revocation needs a comment");
            }
            if (comment != null && comment.length() > 500) {
                throw ApiException.unprocessable("comment_too_long", "comment is limited to 500 characters");
            }
            UUID itemId = uuid(d, "item_id");
            JsonNode item = store.rows("select row_to_json(t)::text from access_review_item t where t.id = ? "
                    + "and t.access_review_id = ? for update", itemId, reviewId).stream().findFirst().map(json::readTree)
                    .orElseThrow(() -> ApiException.unprocessable("unknown_item", "Item " + itemId + " is not part of review "
                            + reviewId));
            JsonNode user = users.computeIfAbsent(item.get("user_id").asString(),
                    id -> store.require("app_user", UUID.fromString(id)));
            if (isUser(user, principal)) {
                throw ApiException.unprocessable("self_review", "Nobody attests or revokes their own access");
            }
            store.jdbc().sql("""
                    update access_review_item set decision = ?, decision_comment = ?, decided_by = ?, decided_at = ?,
                           updated_by = ? where id = ?""")
                    .params(decision, comment, principal, now, principal, itemId).update();
        }
        return recount(reviewId);
    }

    /** Submits a fully decided review for a second person's approval. */
    @Transactional
    public JsonNode submit(UUID reviewId, String idempotencyKey) {
        JsonNode review = store.lock("access_review", reviewId);
        requireStatus(review, "OPEN");
        recount(reviewId);
        Map<String, Integer> counts = counts(reviewId);
        if (counts.getOrDefault("PENDING", 0) > 0) {
            throw ApiException.unprocessable("undecided_items", counts.get("PENDING")
                    + " item(s) still need an attest or revoke decision");
        }
        int revoked = counts.getOrDefault("REVOKE", 0);
        int attested = counts.getOrDefault("ATTEST", 0);
        Integer privilegedRevoked = store.jdbc().sql("""
                select count(*) from access_review_item
                where access_review_id = ? and decision = 'REVOKE' and is_privileged""")
                .param(reviewId).query(Integer.class).single();
        String period = review.get("period").asString();
        ObjectNode payload = json.createObjectNode();
        payload.put("review_id", reviewId.toString());
        payload.put("period", period);
        payload.put("item_count", review.get("item_count").asInt());
        payload.put("attested", attested);
        payload.put("revoked", revoked);
        payload.put("privileged_revoked", privilegedRevoked);
        JsonNode request = workflow.requestApproval(RESOURCE_TYPE, reviewId, ACTION, "Access review " + period,
                "Access recertification " + period + ": " + attested + " assignment(s) attested, " + revoked
                        + " to revoke (" + privilegedRevoked + " privileged)", payload, "HIGH", idempotencyKey);
        String row = store.updateReturning("""
                update access_review t set status = 'SUBMITTED', reviewer = ?, submitted_at = ?, approval_request_id = ?,
                       rejection_reason = null, updated_by = ? where id = ? returning row_to_json(t)::text""",
                TenantContext.principal(), Clocks.now(), UUID.fromString(request.get("id").asString()),
                TenantContext.principal(), reviewId);
        outbox.publish("platform.access_review.submitted", "access_review", reviewId, json.readTree(row));
        return request;
    }

    /** Cancels an OPEN review (e.g. opened by mistake); its items stay as a record. */
    @Transactional
    public String cancel(UUID reviewId, String reason) {
        JsonNode review = store.lock("access_review", reviewId);
        requireStatus(review, "OPEN");
        if (reason == null || reason.isBlank()) {
            throw ApiException.unprocessable("reason_required", "A reason is required");
        }
        return store.updateReturning("""
                update access_review t set status = 'CANCELLED', cancel_reason = ?, updated_by = ?
                where id = ? returning row_to_json(t)::text""", reason, TenantContext.principal(), reviewId);
    }

    /**
     * Applies an approved review (from {@link ApprovalExecutor}, after its four-eyes check): removes every assignment
     * marked REVOKE that still exists, then completes the review. Idempotent.
     */
    @Transactional
    public JsonNode complete(UUID reviewId, UUID approvalId) {
        JsonNode review = store.lock("access_review", reviewId);
        if ("COMPLETED".equals(review.get("status").asString())) {
            return review;
        }
        requireStatus(review, "SUBMITTED");
        if (!approvalId.toString().equals(review.path("approval_request_id").asString(""))) {
            throw ApiException.unprocessable("approval_mismatch", "Review " + reviewId + " was submitted under another "
                    + "approval request");
        }
        OffsetDateTime now = Clocks.now();
        for (String row : store.rows("""
                select row_to_json(t)::text from access_review_item t
                where t.access_review_id = ? and t.decision = 'REVOKE' and t.revoked_at is null for update""", reviewId)) {
            JsonNode item = json.readTree(row);
            UUID userRoleId = UUID.fromString(item.get("user_role_id").asString());
            if (store.find("user_role", userRoleId).isPresent()) {
                userRoles.revoke(userRoleId);
            }
            store.jdbc().sql("update access_review_item set revoked_at = ?, updated_by = ? where id = ?")
                    .params(now, TenantContext.principal(), UUID.fromString(item.get("id").asString())).update();
        }
        String row = store.updateReturning("""
                update access_review t set status = 'COMPLETED', decided_by = ?, completed_at = ?, updated_by = ?
                where id = ? returning row_to_json(t)::text""",
                TenantContext.principal(), now, TenantContext.principal(), reviewId);
        JsonNode done = json.readTree(row);
        outbox.publish("platform.access_review.completed", "access_review", reviewId, done);
        return done;
    }

    /** A rejected (or expired / cancelled) approval returns the review to OPEN for rework. */
    @Transactional
    public void returnForRework(ApprovalDecision d) {
        if (d.resourceId() == null) {
            return;
        }
        JsonNode review = store.find("access_review", d.resourceId()).map(json::readTree).orElse(null);
        if (review == null || !"SUBMITTED".equals(review.get("status").asString())
                || !d.approvalRequestId().toString().equals(review.path("approval_request_id").asString(""))) {
            return;
        }
        String reason = (d.outcome() + " by " + d.decidedBy()).substring(0, Math.min(500,
                (d.outcome() + " by " + d.decidedBy()).length()));
        String row = store.updateReturning("""
                update access_review t set status = 'OPEN', decided_by = ?, rejection_reason = ?, updated_by = ?
                where id = ? returning row_to_json(t)::text""",
                d.decidedBy(), reason, TenantContext.principal(), d.resourceId());
        outbox.publish("platform.access_review.returned", "access_review", d.resourceId(), json.readTree(row));
    }

    // ------------------------------------------------------------------ internals

    private String recount(UUID reviewId) {
        Map<String, Integer> c = counts(reviewId);
        return store.updateReturning("""
                update access_review t set attested_count = ?, revoked_count = ?, updated_by = ?
                where id = ? returning row_to_json(t)::text""",
                c.getOrDefault("ATTEST", 0), c.getOrDefault("REVOKE", 0), TenantContext.principal(), reviewId);
    }

    private Map<String, Integer> counts(UUID reviewId) {
        Map<String, Integer> out = new HashMap<>();
        store.jdbc().sql("select decision, count(*) from access_review_item where access_review_id = ? group by decision")
                .param(reviewId).query((rs, i) -> out.put(rs.getString(1), rs.getInt(2))).list();
        return out;
    }

    private static void requireStatus(JsonNode review, String status) {
        if (!status.equals(review.get("status").asString())) {
            throw ApiException.conflict("review_not_" + status.toLowerCase(), "The access review is "
                    + review.get("status").asString() + ", not " + status);
        }
    }

    static boolean isUser(JsonNode user, String principal) {
        return principal != null && (principal.equals(user.path("username").asString(null))
                || principal.equalsIgnoreCase(user.path("email").asString(""))
                || principal.equals(user.path("external_subject").asString(null))
                || principal.equals(user.path("id").asString(null)));
    }

    private static UUID uuid(JsonNode n, String f) {
        try {
            return UUID.fromString(n.path(f).asString(""));
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("invalid_" + f, f + " must be a UUID");
        }
    }
}
