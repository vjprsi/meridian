package io.meridian.commission.application;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.commission.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * WE-PRICE-1 (PE-11): joining-commission configurations, governed by the same central maker-checker path as commission
 * plans (clone of the commission_plan lifecycle): DRAFT -> PENDING_APPROVAL (submit) -> ACTIVE (approved by a different
 * principal; the previous ACTIVE version of the code is retired) -> RETIRED. Only DRAFT rows are editable.
 */
@Service
public class JoiningCommissionService {
    static final String TABLE = "joining_commission";

    /** Approval resource type for joining-commission configurations in workflow-service (SEC-03). */
    public static final String APPROVAL_RESOURCE = "joining_commission";

    private final Rows rows;
    private final Events events;
    private final Approvals approvals;

    public JoiningCommissionService(Rows rows, Events events, Approvals approvals) {
        this.rows = rows;
        this.events = events;
        this.approvals = approvals;
    }

    @Transactional
    public ObjectNode create(JsonNode req) {
        ObjectNode v = rows.columns(TABLE, req);
        validate(v);
        v.put("status", "DRAFT");
        return rows.insert(TABLE, v);
    }

    public void checkEditable(UUID id, JsonNode patch) {
        ObjectNode p = rows.get(TABLE, id);
        requireDraft(p);
        if (patch.hasNonNull("code")) {
            throw ApiException.unprocessable("field_not_updatable", "code cannot be changed; create a new configuration");
        }
        ObjectNode merged = p.deepCopy();
        patch.properties().forEach(e -> {
            if (!e.getValue().isNull()) {
                merged.set(e.getKey(), e.getValue());
            }
        });
        validate(merged);
    }

    static void validate(JsonNode v) {
        for (String f : new String[] {"register_amount", "first_cash_in_amount", "first_debit_amount", "tax_withholding_rate"}) {
            BigDecimal d = Rows.dec(v, f);
            if (d != null && d.signum() < 0) {
                throw ApiException.unprocessable("invalid_joining_commission", f + " must not be negative");
            }
        }
        LocalDate from = Rows.date(v, "valid_from");
        LocalDate to = Rows.date(v, "valid_to");
        if (from != null && to != null && to.isBefore(from)) {
            throw ApiException.unprocessable("invalid_joining_commission", "valid_to must not be before valid_from");
        }
    }

    static void requireDraft(JsonNode p) {
        if (!"DRAFT".equals(Rows.str(p, "status"))) {
            throw ApiException.conflict("not_editable", "Joining commission " + Rows.str(p, "code") + " is "
                    + Rows.str(p, "status") + "; only a draft can be edited");
        }
    }

    @Transactional
    public ObjectNode submit(UUID id) {
        ObjectNode p = rows.lock(TABLE, id);
        requireDraft(p);
        ObjectNode v = rows.object();
        v.put("status", "PENDING_APPROVAL");
        v.put("submitted_by", TenantContext.principal());
        v.putNull("rejection_reason");
        rows.update(TABLE, id, v);
        rows.execute("update joining_commission set submitted_at = now() where id = ?", id);
        ObjectNode after = rows.get(TABLE, id);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", Rows.str(after, "code"));
        payload.put("name", Rows.str(after, "name"));
        payload.put("currency", Rows.str(after, "currency"));
        payload.put("register_amount", Rows.str(after, "register_amount"));
        payload.put("first_cash_in_amount", Rows.str(after, "first_cash_in_amount"));
        payload.put("first_debit_amount", Rows.str(after, "first_debit_amount"));
        payload.put("submitted_by", Rows.str(after, "submitted_by"));
        payload.put("submitted_at", Rows.str(after, "submitted_at"));
        approvals.request(ApprovalSpec.of(APPROVAL_RESOURCE, id, "approve",
                "Joining commission " + Rows.str(after, "code"), payload),
                "submit:" + id + ":" + Rows.str(after, "submitted_at"));
        return after;
    }

    /** True while the configuration is PENDING_APPROVAL from the same submission. */
    @Transactional(readOnly = true)
    public boolean awaitsApproval(UUID id, String submittedAt) {
        return rows.find(TABLE, id)
                .filter(p -> "PENDING_APPROVAL".equals(Rows.str(p, "status")))
                .filter(p -> submittedAt == null || submittedAt.equals(Rows.str(p, "submitted_at")))
                .isPresent();
    }

    @Transactional
    public ObjectNode approve(UUID id) {
        ObjectNode p = rows.lock(TABLE, id);
        requirePending(p);
        if (TenantContext.principal().equals(Rows.str(p, "submitted_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "A different user must approve the configuration");
        }
        for (ObjectNode old : rows.query("""
                select row_to_json(j)::text from joining_commission j where j.code = ? and j.status = 'ACTIVE' and j.id <> ?
                for update""", Rows.str(p, "code"), id)) {
            rows.execute("update joining_commission set status = 'RETIRED', retired_at = now() where id = ?", Rows.uuid(old, "id"));
        }
        ObjectNode v = rows.object();
        v.put("status", "ACTIVE");
        v.put("approved_by", TenantContext.principal());
        rows.update(TABLE, id, v);
        rows.execute("update joining_commission set approved_at = now() where id = ?", id);
        ObjectNode after = rows.get(TABLE, id);
        events.publish("wallets.joining_commission.activated", TABLE, after);
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "approved directly");
        return after;
    }

    @Transactional
    public ObjectNode reject(UUID id, String reason) {
        ObjectNode p = rows.lock(TABLE, id);
        requirePending(p);
        ObjectNode v = rows.object();
        v.put("status", "DRAFT");
        v.put("rejection_reason", reason);
        ObjectNode after = rows.update(TABLE, id, v);
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "rejected directly");
        return after;
    }

    @Transactional
    public ObjectNode retire(UUID id, LocalDate effective) {
        ObjectNode p = rows.lock(TABLE, id);
        String status = Rows.str(p, "status");
        if (!"ACTIVE".equals(status) && !"DRAFT".equals(status)) {
            throw ApiException.conflict("invalid_transition", "A " + status + " configuration cannot be retired");
        }
        rows.execute("update joining_commission set status = 'RETIRED', retired_at = now(), valid_to = coalesce(?, valid_to) where id = ?",
                effective, id);
        return rows.get(TABLE, id);
    }

    private static void requirePending(JsonNode p) {
        if (!"PENDING_APPROVAL".equals(Rows.str(p, "status"))) {
            throw ApiException.conflict("invalid_transition", "The configuration is " + Rows.str(p, "status") + ", not PENDING_APPROVAL");
        }
    }
}
