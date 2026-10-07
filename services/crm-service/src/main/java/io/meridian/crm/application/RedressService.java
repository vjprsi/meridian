package io.meridian.crm.application;

import static io.meridian.crm.application.Json.text;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.common.error.ApiException.FieldError;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.crm.api.generated.model.ComplaintRedress;
import io.meridian.crm.domain.Lifecycles;
import io.meridian.crm.infrastructure.ExternalServices;
import io.meridian.crm.infrastructure.Rows;
import io.meridian.web.config.TenantSettings;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Complaint redress with four-eyes control: a maker proposes, a different checker approves or rejects, and payment
 * posts a balanced journal entry to ledger-service (debit the bank's redress expense / suspense account, credit the
 * customer's account). A failed posting leaves the redress FAILED (with the ledger's reason) and can be retried;
 * the ledger idempotency key is derived from the redress id, so a retry can never pay twice.
 */
@Service
public class RedressService {
    static final String T = "complaint_redress";
    /** Approval resource type for redress proposals in workflow-service (G11). */
    public static final String APPROVAL_RESOURCE = "complaint_redress";

    private final Rows rows;
    private final CrudEngine crud;
    private final Events events;
    private final ExternalServices external;
    private final TimelineService timeline;
    private final String defaultLedger;
    private final String defaultFunding;
    private final Approvals approvals;

    public RedressService(Rows rows, CrudEngine crud, Events events, ExternalServices external, TimelineService timeline,
                          @Value("${meridian.crm.redress.ledger-id:}") String defaultLedger,
                          @Value("${meridian.crm.redress.funding-account-id:}") String defaultFunding,
                          Approvals approvals) {
        this.approvals = approvals;
        this.rows = rows;
        this.crud = crud;
        this.events = events;
        this.external = external;
        this.timeline = timeline;
        this.defaultLedger = defaultLedger;
        this.defaultFunding = defaultFunding;
    }

    @Transactional
    public ObjectNode create(Object body) {
        ObjectNode v = crud.toColumns(T, body);
        UUID complaintId = Json.uuid(v, "complaint_id");
        ObjectNode complaint = rows.query("select row_to_json(t)::text from complaint t where t.id = ? for update", complaintId)
                .stream().findFirst().orElseThrow(() ->
                        ApiException.unprocessable("complaint_not_found", "Complaint " + complaintId + " does not exist"));
        Lifecycles.COMPLAINT.apply("redress", text(complaint, "status"));
        String type = text(v, "redress_type");
        List<FieldError> errors = new ArrayList<>();
        if (Lifecycles.MONETARY_REDRESS.contains(type)) {
            BigDecimal amount = text(v, "amount") == null ? null : new BigDecimal(text(v, "amount"));
            if (amount == null || amount.signum() <= 0) {
                errors.add(new FieldError("amount", "must_be_positive", "A " + type + " needs an amount > 0"));
            } else if (amount.scale() > 2 && amount.stripTrailingZeros().scale() > 2) {
                errors.add(new FieldError("amount", "invalid_precision", "amount has more than 2 decimals"));
            }
            if (text(v, "currency") == null) {
                errors.add(new FieldError("currency", "required", "currency is required for monetary redress"));
            }
            if (text(v, "target_account_id") == null) {
                errors.add(new FieldError("target_account_id", "required", "The customer account to credit is required"));
            }
            String existing = rows.jdbc().sql("""
                    select currency from complaint_redress where complaint_id = ? and currency is not null
                    and status <> 'REJECTED' limit 1""").param(complaintId).query(String.class).optional().orElse(null);
            if (existing != null && text(v, "currency") != null && !existing.equals(text(v, "currency"))) {
                errors.add(new FieldError("currency", "currency_mismatch", "Redress of this complaint is in " + existing));
            }
        } else if (text(v, "amount") != null || text(v, "target_account_id") != null) {
            errors.add(new FieldError("amount", "not_applicable", type + " redress carries no amount or account"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(422, "invalid_redress", "Redress is invalid", errors);
        }
        v.put("status", "PROPOSED");
        ObjectNode row = rows.insert(T, v);
        events.publish("servicing.complaint_redress.proposed", T, row, ComplaintRedress.class);
        // Central maker-checker: the proposal is the maker's submission; monetary redress is routed by amount.
        UUID id = Json.uuid(row, "id");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("complaint_id", complaintId.toString());
        payload.put("complaint_number", text(complaint, "complaint_number"));
        payload.put("customer_id", text(complaint, "customer_id"));
        payload.put("redress_type", type);
        payload.put("amount", text(row, "amount"));
        payload.put("currency", text(row, "currency"));
        payload.put("target_account_id", text(row, "target_account_id"));
        payload.put("description", text(row, "description"));
        ApprovalSpec spec = ApprovalSpec.of(APPROVAL_RESOURCE, id, "approve",
                "Redress " + type + (text(row, "amount") == null ? "" : " " + text(row, "amount") + " " + text(row, "currency"))
                        + " for complaint " + text(complaint, "complaint_number"), payload);
        if (text(row, "amount") != null && text(row, "currency") != null) {
            spec = spec.withAmount(new BigDecimal(text(row, "amount")), text(row, "currency"));
        }
        approvals.request(spec, "propose:" + id);
        return row;
    }

    @Transactional
    public ObjectNode approve(UUID id, String comment) {
        ObjectNode r = rows.lock(T, id);
        String to = Lifecycles.REDRESS.apply("approve", text(r, "status"));
        String checker = TenantContext.principal();
        if (checker.equals(text(r, "created_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "Redress must be approved by a different user than its maker");
        }
        ObjectNode v = rows.object();
        v.put("status", to);
        v.put("approved_by", Json.truncate(checker, 64));
        v.put("approved_at", rows.now().toString());
        if (comment != null) {
            v.put("approval_comment", Json.truncate(comment, 500));
        }
        ObjectNode row = rows.update(T, id, v);
        events.publish("servicing.complaint_redress.approved", T, row, ComplaintRedress.class);
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "approved directly");
        return row;
    }

    @Transactional
    public ObjectNode reject(UUID id, String reason) {
        ObjectNode r = rows.lock(T, id);
        ObjectNode v = rows.object();
        v.put("status", Lifecycles.REDRESS.apply("reject", text(r, "status")));
        v.put("rejection_reason", Json.truncate(reason, 255));
        ObjectNode row = rows.update(T, id, v);
        events.publish("servicing.complaint_redress.rejected", T, row, ComplaintRedress.class);
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "rejected directly");
        return row;
    }

    /** True while the redress is still a proposal awaiting its checker. */
    @Transactional(readOnly = true)
    public boolean awaitsApproval(UUID id) {
        return rows.find(T, id).filter(r -> "PROPOSED".equals(text(r, "status"))).isPresent();
    }

    /** Pays approved (or previously failed) redress; the answer carries PAID or FAILED with the ledger's reason. */
    @Transactional
    public ObjectNode pay(UUID id) {
        ObjectNode r = rows.lock(T, id);
        String to = Lifecycles.REDRESS.apply("pay", text(r, "status"));
        ObjectNode complaint = rows.lock("complaint", Json.uuid(r, "complaint_id"));
        ObjectNode v = rows.object();
        if (Lifecycles.MONETARY_REDRESS.contains(text(r, "redress_type"))) {
            String ledger = resolve(text(r, "ledger_id"), "redress.ledger_id", defaultLedger);
            String funding = resolve(text(r, "funding_account_id"), "redress.funding_account_id", defaultFunding);
            if (ledger == null) {
                throw ApiException.unprocessable("ledger_not_configured", "No ledger book for redress: set ledger_id on the "
                        + "redress, the tenant's config-service ledger/crm-service.redress.ledger_id or meridian.crm.redress.ledger-id");
            }
            if (funding == null) {
                throw ApiException.unprocessable("redress_funding_not_configured", "No funding account for redress: set "
                        + "funding_account_id on the redress, the tenant's config-service "
                        + "ledger/crm-service.redress.funding_account_id or meridian.crm.redress.funding-account-id");
            }
            try {
                JsonNode entry = external.postJournalEntry(journal(r, complaint, ledger, funding), "crm-redress:" + id);
                v.put("ledger_id", ledger);
                v.put("funding_account_id", funding);
                v.put("journal_entry_id", entry.path("id").asString());
            } catch (ApiException e) {
                v.put("status", "FAILED");
                v.put("failure_reason", Json.truncate(e.code() + ": " + e.getMessage(), 500));
                ObjectNode failed = rows.update(T, id, v);
                events.publish("servicing.complaint_redress.failed", T, failed, ComplaintRedress.class);
                return failed;
            }
        }
        v.put("status", to);
        v.putNull("failure_reason");
        v.put("paid_at", rows.now().toString());
        ObjectNode row = rows.update(T, id, v);
        ObjectNode totals = rows.object();
        rows.jdbc().sql("""
                select currency, sum(amount)::text from complaint_redress where complaint_id = ? and status = 'PAID'
                and amount is not null group by currency limit 1""").param(Json.uuid(r, "complaint_id"))
                .query((rs, i) -> {
                    totals.put("redress_currency", rs.getString(1));
                    totals.put("redress_amount", rs.getString(2));
                    return 1;
                }).list();
        if (!totals.isEmpty()) {
            rows.update("complaint", Json.uuid(r, "complaint_id"), totals);
        }
        events.publish("servicing.complaint_redress.paid", T, row, ComplaintRedress.class);
        timeline.record(Json.uuid(complaint, "customer_id"), rows.now(), "servicing.complaint_redress.paid", "COMPLAINT_REDRESS",
                id, "Redress paid for " + text(complaint, "complaint_number") + ": " + text(r, "redress_type")
                        + (text(r, "amount") == null ? "" : " " + text(r, "amount") + " " + text(r, "currency")),
                Map.of("redress_type", text(r, "redress_type")), 3);
        return row;
    }

    private ObjectNode journal(ObjectNode r, ObjectNode complaint, String ledger, String funding) {
        String narrative = Json.truncate("Complaint redress " + text(complaint, "complaint_number") + " ("
                + text(r, "redress_type") + ")", 500);
        ObjectNode j = rows.object();
        j.put("ledger_id", ledger);
        j.put("entry_type", "ADJUSTMENT");
        j.put("description", narrative);
        j.put("source_type", "COMPLAINT_REDRESS");
        j.put("source_id", text(r, "id"));
        j.put("correlation_id", text(complaint, "complaint_number"));
        ArrayNode entries = j.putArray("entries");
        ObjectNode debit = entries.addObject();
        debit.put("ledger_account_id", funding);
        debit.put("direction", "DEBIT");
        debit.put("amount", text(r, "amount"));
        debit.put("currency", text(r, "currency"));
        debit.put("description", narrative);
        ObjectNode credit = entries.addObject();
        credit.put("ledger_account_id", text(r, "target_account_id"));
        credit.put("direction", "CREDIT");
        credit.put("amount", text(r, "amount"));
        credit.put("currency", text(r, "currency"));
        credit.put("description", narrative);
        return j;
    }

    /**
     * A redress ledger setting: the redress's own value, else the tenant's config-service setting (namespace
     * {@code ledger}, key {@code crm-service}, {@code path}), else the deployment property; null when none is set.
     */
    static String resolve(String own, String path, String deployment) {
        if (own != null) {
            return own;
        }
        UUID tenant = TenantSettings.uuid("crm-service", path, null);
        return tenant != null ? tenant.toString() : blankToNull(deployment);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
