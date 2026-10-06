package io.meridian.collections.application;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.collections.domain.Currencies;
import io.meridian.collections.domain.Lifecycles;
import io.meridian.collections.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Legal proceedings: proposal, four-eyes approval to litigate, court stages. */
@Service
public class LegalService {
    static final String TABLE = "legal_proceeding";

    /** Approval resource type for legal proceedings (approval to litigate) in workflow-service (G11). */
    public static final String APPROVAL_RESOURCE = "legal_proceeding";

    private final Rows rows;
    private final Events events;
    private final CaseService cases;
    private final Approvals approvals;

    public LegalService(Rows rows, Events events, CaseService cases, Approvals approvals) {
        this.rows = rows;
        this.events = events;
        this.cases = cases;
        this.approvals = approvals;
    }

    @Transactional
    public ObjectNode start(UUID caseId, BigDecimal claim, String lawFirm, String notes) {
        ObjectNode c = cases.lockOpen(caseId);
        if (claim == null || claim.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount", "claim_amount must be positive");
        }
        if (claim.compareTo(Rows.dec(c, "total_outstanding")) > 0) {
            throw ApiException.unprocessable("amount_exceeds_outstanding", "claim_amount exceeds the total outstanding");
        }
        if (Rows.bool(c, "is_disputed")) {
            throw ApiException.conflict("case_disputed", "Resolve the dispute before starting legal proceedings");
        }
        int open = rows.scalar("""
                select count(*) from legal_proceeding where collection_case_id = ?
                  and status not in ('REJECTED', 'SETTLED', 'CLOSED')""", Integer.class, caseId).orElse(0);
        if (open > 0) {
            throw ApiException.conflict("legal_proceeding_exists", "The case already has legal proceedings");
        }
        ObjectNode row = rows.object();
        row.put("collection_case_id", caseId.toString());
        row.put("claim_amount", claim.toPlainString());
        row.put("currency", Rows.str(c, "currency"));
        row.put("status", "PENDING_APPROVAL");
        row.put("requested_by", TenantContext.principal());
        if (lawFirm != null) {
            row.put("law_firm", lawFirm);
        }
        if (notes != null) {
            row.put("notes", notes);
        }
        ObjectNode out = rows.insert(TABLE, row);
        events.publish("lending.legal_proceeding.requested", TABLE, out);
        // Central maker-checker: the checker decides in the workflow inbox; LegalProceedingApprovalHandler applies it.
        UUID id = Rows.uuid(out, "id");
        String ccy = Rows.str(out, "currency");
        BigDecimal amount = Currencies.round(Rows.dec(out, "claim_amount"), ccy);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("collection_case_id", caseId.toString());
        payload.put("customer_id", Rows.str(c, "customer_id"));
        payload.put("exposure_type", Rows.str(c, "exposure_type"));
        payload.put("exposure_id", Rows.str(c, "exposure_id"));
        payload.put("days_past_due", Rows.integer(c, "days_past_due"));
        payload.put("total_outstanding", Currencies.round(Rows.dec(c, "total_outstanding"), ccy).toPlainString());
        payload.put("claim_amount", amount.toPlainString());
        payload.put("currency", ccy);
        payload.put("law_firm", lawFirm);
        payload.put("notes", notes);
        approvals.request(ApprovalSpec.of(APPROVAL_RESOURCE, id, "approve",
                        "Legal proceedings on case " + Rows.str(c, "case_number"), payload)
                        .withAmount(amount, ccy),
                "start-legal:" + id);
        return out;
    }

    @Transactional
    public ObjectNode approve(UUID id, String comment) {
        ObjectNode l = rows.lock(TABLE, id);
        Lifecycles.require(Lifecycles.LEGAL, "Legal proceeding", Rows.str(l, "status"), "INITIATED");
        String principal = TenantContext.principal();
        if (principal.equals(Rows.str(l, "requested_by"))) {
            throw ApiException.unprocessable("four_eyes_violation",
                    "Litigation must be approved by a different user than the one who requested it");
        }
        UUID caseId = Rows.uuid(l, "collection_case_id");
        ObjectNode c = cases.lockOpen(caseId);
        ObjectNode v = rows.object();
        v.put("status", "INITIATED");
        v.put("approved_by", principal);
        if (comment != null) {
            v.put("notes", append(Rows.str(l, "notes"), "Approved: " + comment));
        }
        ObjectNode out = rows.update(TABLE, id, v);
        cases.setStatus(caseId, Rows.str(c, "status"), "LEGAL");
        events.publish("lending.legal_proceeding.started", TABLE, out);
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "approved directly");
        return out;
    }

    /** True while the proceeding is PENDING_APPROVAL with the submitted claim (fingerprint) unchanged. */
    @Transactional(readOnly = true)
    public boolean awaitsApproval(UUID id, String claimAmount) {
        return rows.find(TABLE, id)
                .filter(l -> "PENDING_APPROVAL".equals(Rows.str(l, "status")))
                .filter(l -> claimAmount == null
                        || new BigDecimal(claimAmount).compareTo(Rows.dec(l, "claim_amount")) == 0)
                .isPresent();
    }

    @Transactional
    public ObjectNode reject(UUID id, String reason) {
        ObjectNode l = rows.lock(TABLE, id);
        Lifecycles.require(Lifecycles.LEGAL, "Legal proceeding", Rows.str(l, "status"), "REJECTED");
        if (TenantContext.principal().equals(Rows.str(l, "requested_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "The requester cannot review their own request");
        }
        ObjectNode v = rows.object();
        v.put("status", "REJECTED");
        v.put("approved_by", TenantContext.principal());
        v.put("notes", append(Rows.str(l, "notes"), "Rejected: " + reason));
        ObjectNode out = rows.update(TABLE, id, v);
        events.publish("lending.legal_proceeding.status_changed", TABLE, out);
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "rejected directly");
        return out;
    }

    @Transactional
    public ObjectNode advance(UUID id, JsonNode req) {
        ObjectNode l = rows.lock(TABLE, id);
        String to = Rows.str(req, "status");
        Lifecycles.require(Lifecycles.LEGAL, "Legal proceeding", Rows.str(l, "status"), to);
        ObjectNode v = rows.object();
        v.put("status", to);
        for (String f : new String[] {"court", "case_reference", "filed_at", "next_hearing_date", "judgement_amount",
                "legal_costs"}) {
            if (req.hasNonNull(f)) {
                v.set(f, req.get(f));
            }
        }
        if (req.hasNonNull("notes")) {
            v.put("notes", append(Rows.str(l, "notes"), req.get("notes").asString()));
        }
        if ("FILED".equals(to) && (value(v, l, "court") == null || value(v, l, "case_reference") == null)) {
            throw ApiException.unprocessable("filing_details_required", "court and case_reference are required to file");
        }
        if ("FILED".equals(to) && value(v, l, "filed_at") == null) {
            v.put("filed_at", rows.dbToday().toString());
        }
        if ("HEARING".equals(to) && value(v, l, "next_hearing_date") == null) {
            throw ApiException.unprocessable("hearing_date_required", "next_hearing_date is required for a hearing");
        }
        if ("JUDGEMENT".equals(to) && value(v, l, "judgement_amount") == null) {
            throw ApiException.unprocessable("judgement_amount_required", "judgement_amount is required for a judgement");
        }
        if (req.hasNonNull("legal_costs") && new BigDecimal(req.get("legal_costs").asString()).signum() < 0) {
            throw ApiException.unprocessable("invalid_amount", "legal_costs must not be negative");
        }
        ObjectNode out = rows.update(TABLE, id, v);
        events.publish("lending.legal_proceeding.status_changed", TABLE, out);
        return out;
    }

    private static String value(ObjectNode change, ObjectNode current, String f) {
        String s = Rows.str(change, f);
        return s != null ? s : Rows.str(current, f);
    }

    private static String append(String notes, String line) {
        return notes == null || notes.isBlank() ? line : notes + "\n" + line;
    }
}
