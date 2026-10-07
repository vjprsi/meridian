package io.meridian.businesschannel.application;

import static io.meridian.businesschannel.application.CurrentUser.business;

import io.meridian.businesschannel.domain.ApprovalPolicy;
import io.meridian.businesschannel.domain.ApprovalPolicy.Rule;
import io.meridian.common.error.ApiException;
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
 * The business's approval matrix (ADMIN; step-up). Rules cover payments ({@code ALL}, {@code SINGLE}, {@code BULK}) or
 * one non-payment operation type ({@link ApprovalPolicy#OPERATION_TYPES}). Rules are never edited in place: a replacement deactivates the old
 * version (kept, because submitted payments reference the rule they were planned with) and inserts a new one. Bands of
 * one currency and overlapping scope must not overlap; named approvers must be ACTIVE APPROVER or ADMIN users of the
 * business. Matrix changes apply to payments submitted afterwards (a pending payment keeps its snapshot).
 */
@Service
public class ApprovalRuleService {
    static final String RULES = "business_approval_rule";
    static final Set<String> SCOPES = java.util.stream.Stream.concat(Set.of("ALL", "SINGLE", "BULK").stream(),
            ApprovalPolicy.OPERATION_TYPES.stream()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    static final Set<String> ORDERS = Set.of("ANY_ORDER", "SEQUENTIAL");

    private final Rows rows;
    private final Access access;
    private final StepUp stepUp;
    private final ActionAudit audit;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;

    public ApprovalRuleService(Rows rows, Access access, StepUp stepUp, ActionAudit audit, OutboxWriter outbox,
                               TransactionTemplate tx) {
        this.rows = rows;
        this.access = access;
        this.stepUp = stepUp;
        this.audit = audit;
        this.outbox = outbox;
        this.tx = tx;
    }

    public JsonNode list() {
        return rows.page(active());
    }

    /** Active rules of the business as policy rules. */
    public List<Rule> activeRules() {
        return active().stream().map(ApprovalRuleService::rule).toList();
    }

    public JsonNode create(JsonNode req) {
        String action = "businessApprovalRulesCreate";
        access.requireAdmin(action, "RULE", null);
        ObjectNode values = values(req);
        stepUp.require(action, "RULE", null);
        ObjectNode saved = tx.execute(s -> {
            rows.lockKey("business-rules:" + business());
            checkOverlap(values, null);
            ObjectNode r = rows.insert(RULES, values);
            changed("ADDED", r);
            return r;
        });
        audit.succeeded(action, "RULE", saved.path("id").asString(), saved.path("id").asString());
        return saved;
    }

    public JsonNode replace(UUID id, JsonNode req) {
        String action = "businessApprovalRulesReplace";
        access.requireAdmin(action, "RULE", id);
        ObjectNode old = activeRule(id, action);
        ObjectNode values = values(req);
        stepUp.require(action, "RULE", id);
        ObjectNode saved = tx.execute(s -> {
            rows.lockKey("business-rules:" + business());
            checkOverlap(values, id);
            rows.update(RULES, id, rows.object().put("is_active", false));
            ObjectNode r = rows.insert(RULES, values);
            changed("REPLACED", r.deepCopy().put("replaces_rule_id", old.path("id").asString()));
            return r;
        });
        audit.succeeded(action, "RULE", id, saved.path("id").asString());
        return saved;
    }

    public void delete(UUID id) {
        String action = "businessApprovalRulesDelete";
        access.requireAdmin(action, "RULE", id);
        activeRule(id, action);
        stepUp.require(action, "RULE", id);
        tx.executeWithoutResult(s -> changed("DEACTIVATED", rows.update(RULES, id, rows.object().put("is_active", false))));
        audit.succeeded(action, "RULE", id, null);
    }

    // ------------------------------------------------------------------ helpers

    private List<ObjectNode> active() {
        return rows.list("select row_to_json(t)::text from business_approval_rule t where t.business_customer_id = ? "
                + "and t.is_active order by t.currency, t.min_amount", business());
    }

    private ObjectNode activeRule(UUID id, String action) {
        ObjectNode r = rows.find(RULES, business(), id).orElseThrow(() -> access.deny(action, "RULE", id));
        if (!r.path("is_active").asBoolean(false)) {
            throw ApiException.conflict("rule_inactive", "The rule was already replaced or deactivated");
        }
        return r;
    }

    private ObjectNode values(JsonNode req) {
        String scope = Req.text(req, "applies_to") == null ? "ALL" : Req.text(req, "applies_to");
        String order = Req.text(req, "approval_order") == null ? "ANY_ORDER" : Req.text(req, "approval_order");
        if (!SCOPES.contains(scope)) {
            throw ApiException.unprocessable("invalid_value", "applies_to must be one of " + SCOPES);
        }
        if (!ORDERS.contains(order)) {
            throw ApiException.unprocessable("invalid_value", "approval_order must be one of " + ORDERS);
        }
        BigDecimal min = Req.decimal(req, "min_amount");
        BigDecimal max = Req.decimal(req, "max_amount");
        Integer required = Req.integer(req, "required_approvals");
        List<UUID> approvers = new ArrayList<>();
        JsonNode ids = req.get("approver_ids");
        if (ids != null && ids.isArray()) {
            for (JsonNode x : ids) {
                try {
                    approvers.add(UUID.fromString(x.asString()));
                } catch (IllegalArgumentException e) {
                    throw ApiException.badRequest("invalid_value", "approver_ids must be UUIDs");
                }
            }
        }
        Rule rule = new Rule(null, scope, Req.required(req, "currency").toUpperCase(), min == null ? BigDecimal.ZERO : min,
                max, required == null ? 0 : required, order, approvers);
        List<String> problems = ApprovalPolicy.problems(rule);
        if (!problems.isEmpty()) {
            throw ApiException.unprocessable("invalid_approval_rule", String.join("; ", problems));
        }
        for (UUID a : approvers) {
            ObjectNode u = rows.find("business_user", business(), a)
                    .orElseThrow(() -> ApiException.unprocessable("approver_not_found", "Approver " + a
                            + " is not a user of the business"));
            if (!"ACTIVE".equals(u.path("status").asString()) || !Set.of("ADMIN", "APPROVER").contains(u.path("role").asString())) {
                throw ApiException.unprocessable("approver_not_eligible", "Approver " + a
                        + " must be an ACTIVE APPROVER or ADMIN user");
            }
        }
        ObjectNode v = rows.object()
                .put("business_customer_id", business().toString())
                .put("name", Req.required(req, "name"))
                .put("applies_to", scope)
                .put("currency", rule.currency())
                .put("min_amount", rule.min().toPlainString())
                .put("max_amount", max == null ? null : max.toPlainString())
                .put("required_approvals", rule.required())
                .put("approval_order", order)
                .put("is_active", true);
        ArrayNode arr = v.putArray("approver_ids");
        approvers.forEach(a -> arr.add(a.toString()));
        return v;
    }

    private void checkOverlap(ObjectNode values, UUID replacing) {
        Rule candidate = rule(values);
        for (ObjectNode other : active()) {
            if (replacing != null && replacing.toString().equals(other.path("id").asString())) {
                continue;
            }
            if (ApprovalPolicy.overlaps(candidate, rule(other))) {
                throw ApiException.conflict("approval_rule_overlap", "The band overlaps rule '"
                        + other.path("name").asString() + "' (" + other.path("currency").asString() + " "
                        + other.path("min_amount").asString() + " - " + other.path("max_amount").asString("open") + ")");
            }
        }
    }

    static Rule rule(JsonNode r) {
        String id = Req.text(r, "id");
        return new Rule(id == null ? null : UUID.fromString(id), Req.text(r, "applies_to"), Req.text(r, "currency"),
                Req.rowDecimal(r, "min_amount"), Req.rowDecimal(r, "max_amount"), r.path("required_approvals").asInt(),
                Req.text(r, "approval_order"), UserAdminService.uuids(r.get("approver_ids")));
    }

    private void changed(String change, ObjectNode rule) {
        outbox.publish("channels.business_approval_rule.changed", RULES, UUID.fromString(rule.path("id").asString()),
                rows.object().put("change", change).set("rule", rule));
    }
}
