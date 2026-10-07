package io.meridian.commission.application;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.commission.domain.CommissionEngine;
import io.meridian.commission.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Versioned commission plans: DRAFT -> PENDING_APPROVAL (submit) -> ACTIVE (approve by a different principal; the
 * previous active version of the code is retired) -> RETIRED. Only DRAFT plans and their rules can be edited; a new
 * version is created by cloning.
 */
@Service
public class PlanService {
    static final String PLAN = "commission_plan";
    static final String RULE = "commission_rule";
    static final Set<String> APPLIES_TO = Set.of("AGENT", "MERCHANT", "BILLER", "ALL");
    static final Set<String> FUNDED_BY = Set.of("FEE", "PLATFORM", "BILLER", "MERCHANT");

    /** Approval resource type for commission plan versions in workflow-service (G11). */
    public static final String APPROVAL_RESOURCE = "commission_plan";

    private final Rows rows;
    private final Events events;
    private final Hierarchy hierarchy;
    private final Approvals approvals;

    public PlanService(Rows rows, Events events, Hierarchy hierarchy, Approvals approvals) {
        this.approvals = approvals;
        this.rows = rows;
        this.events = events;
        this.hierarchy = hierarchy;
    }

    @Transactional
    public ObjectNode create(JsonNode req) {
        ObjectNode v = rows.columns(PLAN, req);
        validatePlan(v);
        rows.advisoryLock("commission_plan:" + Rows.str(v, "code"));
        v.put("version", nextVersion(Rows.str(v, "code")));
        v.put("status", "DRAFT");
        return rows.insert(PLAN, v);
    }

    int nextVersion(String code) {
        return rows.scalar("select coalesce(max(version), 0) + 1 from commission_plan where code = ?", Integer.class, code)
                .orElse(1);
    }

    static void validatePlan(JsonNode v) {
        if (v.hasNonNull("applies_to") && !APPLIES_TO.contains(Rows.str(v, "applies_to"))) {
            throw ApiException.unprocessable("invalid_plan", "applies_to must be one of " + APPLIES_TO);
        }
        LocalDate from = Rows.date(v, "valid_from");
        LocalDate to = Rows.date(v, "valid_to");
        if (from != null && to != null && to.isBefore(from)) {
            throw ApiException.unprocessable("invalid_plan", "valid_to must not be before valid_from");
        }
    }

    /** PATCH guard: only DRAFT plans change; code and status are fixed. */
    public void checkEditable(UUID planId, JsonNode patch) {
        ObjectNode p = rows.get(PLAN, planId);
        requireDraft(p);
        if (patch.hasNonNull("code")) {
            throw ApiException.unprocessable("field_not_updatable", "code cannot be changed; create a new plan");
        }
        ObjectNode merged = p.deepCopy();
        patch.properties().forEach(e -> {
            if (!e.getValue().isNull()) {
                merged.set(e.getKey(), e.getValue());
            }
        });
        validatePlan(merged);
    }

    static void requireDraft(JsonNode plan) {
        if (!"DRAFT".equals(Rows.str(plan, "status"))) {
            throw ApiException.conflict("plan_not_editable", "Plan " + Rows.str(plan, "code") + " v"
                    + Rows.integer(plan, "version") + " is " + Rows.str(plan, "status") + "; clone it to make changes");
        }
    }

    // ------------------------------------------------------------------ rules

    /** Validates a rule (merged with its current values for a PATCH) and that its plan is a DRAFT. */
    public void checkRule(JsonNode rule) {
        UUID planId = Rows.uuid(rule, "commission_plan_id");
        ObjectNode plan = rows.find(PLAN, planId).orElseThrow(() ->
                ApiException.unprocessable("plan_not_found", "commission_plan_id does not exist"));
        requireDraft(plan);
        if (rule.hasNonNull("funded_by") && !FUNDED_BY.contains(Rows.str(rule, "funded_by"))) {
            throw ApiException.unprocessable("invalid_rule", "funded_by must be one of " + FUNDED_BY);
        }
        CommissionEngine.validate(toRule(withDefaults(rule)));
    }

    /** Guard for rule PATCH / DELETE: the plan of the existing rule must be a DRAFT. */
    public ObjectNode ruleOfDraft(UUID ruleId) {
        ObjectNode r = rows.get(RULE, ruleId);
        requireDraft(rows.get(PLAN, Rows.uuid(r, "commission_plan_id")));
        return r;
    }

    static ObjectNode withDefaults(JsonNode rule) {
        ObjectNode r = ((ObjectNode) rule).deepCopy();
        if (!r.hasNonNull("base")) {
            r.put("base", "FEE");
        }
        if (!r.hasNonNull("beneficiary_share")) {
            r.put("beneficiary_share", "1");
        }
        if (!r.hasNonNull("priority")) {
            r.put("priority", 100);
        }
        if (!r.hasNonNull("is_active")) {
            r.put("is_active", true);
        }
        if (!r.hasNonNull("id")) {
            r.put("id", "00000000-0000-0000-0000-000000000000");
        }
        return r;
    }

    public static CommissionEngine.Rule toRule(JsonNode r) {
        List<CommissionEngine.Tier> tiers = null;
        JsonNode t = r.get("tiers");
        if (t != null && t.isArray()) {
            tiers = new ArrayList<>();
            for (JsonNode x : t) {
                tiers.add(new CommissionEngine.Tier(Rows.dec(x, "from"), Rows.dec(x, "to"), Rows.dec(x, "flat"),
                        Rows.dec(x, "percentage")));
            }
        }
        BigDecimal share = Rows.dec(r, "beneficiary_share");
        return new CommissionEngine.Rule(Rows.uuid(r, "id"), Rows.integer(r, "priority") == null ? 100 : Rows.integer(r, "priority"),
                Rows.str(r, "txn_type"), Rows.str(r, "channel"), Rows.integer(r, "kyc_tier"), Rows.str(r, "agent_level"),
                Rows.uuid(r, "agent_territory_id"), Rows.uuid(r, "biller_id"), Rows.dec(r, "min_amount"),
                Rows.dec(r, "max_amount"), Rows.str(r, "basis"), Rows.dec(r, "flat_amount"), Rows.dec(r, "percentage"),
                tiers, Rows.dec(r, "cap_amount"), Rows.str(r, "base") == null ? "FEE" : Rows.str(r, "base"),
                Rows.str(r, "beneficiary"), Rows.uuid(r, "beneficiary_wallet_id"), share == null ? BigDecimal.ONE : share,
                Rows.dec(r, "tax_withholding_rate"), !r.hasNonNull("is_active") || Rows.bool(r, "is_active"));
    }

    public List<CommissionEngine.Rule> rules(UUID planId) {
        return rows.query("select row_to_json(r)::text from commission_rule r where r.commission_plan_id = ? order by r.id",
                planId).stream().map(PlanService::toRule).toList();
    }

    // ------------------------------------------------------------------ lifecycle

    @Transactional
    public ObjectNode submit(UUID id) {
        ObjectNode p = rows.lock(PLAN, id);
        requireDraft(p);
        long active = rows.scalar("select count(*) from commission_rule where commission_plan_id = ? and is_active",
                Long.class, id).orElse(0L);
        if (active == 0) {
            throw ApiException.unprocessable("plan_has_no_rules", "A plan needs at least one active rule before submission");
        }
        ObjectNode v = rows.object();
        v.put("status", "PENDING_APPROVAL");
        v.put("submitted_by", TenantContext.principal());
        v.putNull("rejection_reason");
        rows.update(PLAN, id, v);
        rows.execute("update commission_plan set submitted_at = now() where id = ?", id);
        ObjectNode after = rows.get(PLAN, id);
        events.publish("wallets.commission_plan.submitted", PLAN, after);
        // Central maker-checker: the checker decides in the workflow inbox; PlanApprovalHandler applies the decision.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", Rows.str(after, "code"));
        payload.put("version", Rows.integer(after, "version"));
        payload.put("name", Rows.str(after, "name"));
        payload.put("applies_to", Rows.str(after, "applies_to"));
        payload.put("currency", Rows.str(after, "currency"));
        payload.put("payout_frequency", Rows.str(after, "payout_frequency"));
        payload.put("valid_from", Rows.str(after, "valid_from"));
        payload.put("valid_to", Rows.str(after, "valid_to"));
        payload.put("active_rule_count", active);
        payload.put("submitted_by", Rows.str(after, "submitted_by"));
        payload.put("submitted_at", Rows.str(after, "submitted_at"));
        approvals.request(ApprovalSpec.of(APPROVAL_RESOURCE, id, "approve",
                "Commission plan " + Rows.str(after, "code") + " v" + Rows.integer(after, "version"), payload),
                "submit:" + id + ":" + Rows.str(after, "submitted_at"));
        return after;
    }

    @Transactional
    public ObjectNode approve(UUID id) {
        ObjectNode p = rows.lock(PLAN, id);
        requirePending(p);
        if (TenantContext.principal().equals(Rows.str(p, "submitted_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "The plan must be approved by a different user than its submitter");
        }
        for (ObjectNode old : rows.query("""
                select row_to_json(p)::text from commission_plan p where p.code = ? and p.status = 'ACTIVE' and p.id <> ?
                for update""", Rows.str(p, "code"), id)) {
            retireRow(Rows.uuid(old, "id"), null);
        }
        ObjectNode v = rows.object();
        v.put("status", "ACTIVE");
        v.put("approved_by", TenantContext.principal());
        rows.update(PLAN, id, v);
        rows.execute("update commission_plan set approved_at = now() where id = ?", id);
        ObjectNode after = rows.get(PLAN, id);
        events.publish("wallets.commission_plan.activated", PLAN, after);
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "approved directly");
        return after;
    }

    @Transactional
    public ObjectNode reject(UUID id, String reason) {
        ObjectNode p = rows.lock(PLAN, id);
        requirePending(p);
        ObjectNode v = rows.object();
        v.put("status", "DRAFT");
        v.put("rejection_reason", reason);
        ObjectNode after = rows.update(PLAN, id, v);
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "rejected directly");
        return after;
    }

    /** True while the plan is PENDING_APPROVAL from the same submission (submitted_at unchanged). */
    @Transactional(readOnly = true)
    public boolean awaitsApproval(UUID id, String submittedAt) {
        return rows.find(PLAN, id)
                .filter(p -> "PENDING_APPROVAL".equals(Rows.str(p, "status")))
                .filter(p -> submittedAt == null || submittedAt.equals(Rows.str(p, "submitted_at")))
                .isPresent();
    }

    @Transactional
    public ObjectNode retire(UUID id, LocalDate effective) {
        ObjectNode p = rows.lock(PLAN, id);
        String status = Rows.str(p, "status");
        if (!"ACTIVE".equals(status) && !"DRAFT".equals(status)) {
            throw ApiException.conflict("invalid_transition", "A " + status + " plan cannot be retired");
        }
        return retireRow(id, effective);
    }

    private ObjectNode retireRow(UUID id, LocalDate effective) {
        if (effective != null) {
            rows.execute("update commission_plan set status = 'RETIRED', retired_at = now(), valid_to = ? where id = ?", effective, id);
        } else {
            LocalDate today = rows.today();
            rows.execute("""
                    update commission_plan set status = 'RETIRED', retired_at = now(),
                           valid_to = least(coalesce(valid_to, ?), ?) where id = ?""", today, today, id);
        }
        rows.execute("update commission_plan set valid_to = greatest(valid_to, valid_from) where id = ?", id);
        ObjectNode after = rows.get(PLAN, id);
        events.publish("wallets.commission_plan.retired", PLAN, after);
        return after;
    }

    @Transactional
    public ObjectNode clone(UUID id) {
        ObjectNode p = rows.get(PLAN, id);
        rows.advisoryLock("commission_plan:" + Rows.str(p, "code"));
        ObjectNode v = rows.object();
        for (String f : List.of("code", "name", "description", "applies_to", "currency", "payout_frequency", "valid_from",
                "valid_to")) {
            if (p.hasNonNull(f)) {
                v.set(f, p.get(f));
            }
        }
        v.put("version", nextVersion(Rows.str(p, "code")));
        v.put("status", "DRAFT");
        v.put("parent_plan_id", id.toString());
        ObjectNode copy = rows.insert(PLAN, v);
        for (ObjectNode r : rows.query("select row_to_json(r)::text from commission_rule r where r.commission_plan_id = ? order by r.id", id)) {
            ObjectNode c = rows.columns(RULE, r);
            for (String f : List.of("id", "created_at", "created_by", "updated_at", "updated_by", "row_version", "tenant_id")) {
                c.remove(f);
            }
            c.put("commission_plan_id", Rows.str(copy, "id"));
            rows.insert(RULE, c);
        }
        return copy;
    }

    private static void requirePending(JsonNode p) {
        if (!"PENDING_APPROVAL".equals(Rows.str(p, "status"))) {
            throw ApiException.conflict("invalid_transition", "The plan is " + Rows.str(p, "status") + ", not PENDING_APPROVAL");
        }
    }

    /** Commissions a hypothetical transaction would generate under this plan (nothing stored). */
    public ObjectNode simulate(UUID id, JsonNode req) {
        ObjectNode p = rows.get(PLAN, id);
        BigDecimal amount = Rows.dec(req, "amount");
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount", "amount must be positive");
        }
        BigDecimal fee = Rows.dec(req, "fee_amount");
        Hierarchy.Context ctx = hierarchy.resolve(Rows.uuid(req, "agent_id"));
        CommissionEngine.Txn txn = new CommissionEngine.Txn(Rows.str(req, "txn_type"), Rows.str(req, "channel"),
                Rows.integer(req, "kyc_tier"), ctx.agentLevel(), ctx.territoryId(), Rows.uuid(req, "biller_id"), amount, fee);
        ObjectNode out = rows.object();
        out.put("commission_plan_id", id.toString());
        out.put("txn_type", txn.txnType());
        out.put("amount", amount.toPlainString());
        out.put("fee_amount", fee == null ? "0" : fee.toPlainString());
        out.put("currency", Rows.str(p, "currency"));
        ArrayNode arr = out.putArray("earnings");
        BigDecimal total = BigDecimal.ZERO;
        for (CommissionEngine.Selected s : CommissionEngine.evaluate(rules(id), txn)) {
            Hierarchy.Beneficiary b = hierarchy.beneficiary(s.rule(), ctx, null);
            ObjectNode e = arr.addObject();
            e.put("commission_rule_id", s.rule().id().toString());
            e.put("beneficiary", s.rule().beneficiary());
            e.put("beneficiary_agent_id", b == null || b.agentId() == null ? null : b.agentId().toString());
            e.put("beneficiary_wallet_id", b == null || b.walletId() == null ? null : b.walletId().toString());
            e.put("base_amount", s.amounts().base().toPlainString());
            e.put("commission_amount", s.amounts().commission().toPlainString());
            e.put("tax_amount", s.amounts().tax().toPlainString());
            e.put("net_amount", s.amounts().net().toPlainString());
            total = total.add(s.amounts().commission());
        }
        out.put("total_commission", total.toPlainString());
        return out;
    }
}
