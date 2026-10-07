package io.meridian.agentnetwork.application;

import io.meridian.agentnetwork.domain.Hierarchy;
import io.meridian.agentnetwork.infrastructure.Rows;
import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Agent KYB documents (four-eyes verification) and cash-in-transit requests for outlets. */
@Service
public class ComplianceService {
    static final Set<String> DOC_TYPES = Set.of("BUSINESS_LICENSE", "TAX_CERTIFICATE", "ID", "PROOF_OF_ADDRESS", "CONTRACT");
    static final String DOC = "agent_kyc_document";
    static final String CMR = "cash_management_request";
    /** Approval resource types in workflow-service (G11). */
    public static final String DOC_APPROVAL = "agent_kyc_document";
    public static final String CASH_APPROVAL = "cash_management_request";

    private final Rows rows;
    private final Events events;
    private final Approvals approvals;

    public ComplianceService(Rows rows, Events events, Approvals approvals) {
        this.rows = rows;
        this.events = events;
        this.approvals = approvals;
    }

    @Transactional
    public ObjectNode createDocument(JsonNode req) {
        ObjectNode v = rows.columns(DOC, req);
        if (!DOC_TYPES.contains(Rows.str(v, "document_type"))) {
            throw ApiException.unprocessable("invalid_document_type", "document_type must be one of " + DOC_TYPES);
        }
        ObjectNode agent = rows.find("agent", Rows.uuid(v, "agent_id")).orElseThrow(() ->
                ApiException.unprocessable("agent_not_found", "The agent does not exist"));
        if ("TERMINATED".equals(Rows.str(agent, "status"))) {
            throw ApiException.unprocessable("agent_terminated", "The agent is terminated");
        }
        if (Rows.str(v, "expires_at") != null && java.time.LocalDate.parse(Rows.str(v, "expires_at")).isBefore(rows.today())) {
            throw ApiException.unprocessable("document_expired", "The document has already expired");
        }
        v.put("verified", false);
        ObjectNode row = rows.insert(DOC, v);
        // Central maker-checker: the checker verifies from the workflow inbox; the handler applies the decision.
        UUID id = Rows.uuid(row, "id");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("agent_id", Rows.str(row, "agent_id"));
        payload.put("agent_code", Rows.str(agent, "agent_code"));
        payload.put("agent_name", Rows.str(agent, "legal_name"));
        payload.put("document_type", Rows.str(row, "document_type"));
        payload.put("document_id", Rows.str(row, "document_id"));
        payload.put("expires_at", Rows.str(row, "expires_at"));
        approvals.request(ApprovalSpec.of(DOC_APPROVAL, id, "verify",
                "KYB document " + Rows.str(row, "document_type") + " for agent " + Rows.str(agent, "agent_code"), payload),
                "submit:" + id + ":" + Rows.str(row, "document_id"));
        return row;
    }

    /** True while the document is unverified and still refers to the submitted stored document. */
    @Transactional(readOnly = true)
    public boolean documentAwaitsVerification(UUID id, String documentId) {
        return rows.find(DOC, id)
                .filter(d -> !Rows.bool(d, "verified"))
                .filter(d -> documentId == null || documentId.equals(Rows.str(d, "document_id")))
                .isPresent();
    }

    @Transactional
    public ObjectNode verifyDocument(UUID id, String comment) {
        ObjectNode d = rows.lock(DOC, id);
        if (Rows.bool(d, "verified")) {
            return d;
        }
        if (TenantContext.principal().equals(Rows.str(d, "created_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "The document must be verified by a different user than its uploader");
        }
        if (rows.scalar("select expires_at is not null and expires_at < ? from agent_kyc_document where id = ?",
                Boolean.class, rows.today(), id).orElse(false)) {
            throw ApiException.unprocessable("document_expired", "An expired document cannot be verified");
        }
        rows.execute("update agent_kyc_document set verified = true, verified_by = ?, verified_at = now() where id = ?",
                TenantContext.principal(), id);
        ObjectNode row = rows.get(DOC, id);
        events.publish("wallets.agent_kyc_document.verified", DOC, row);
        approvals.cancelOpen(DOC_APPROVAL, id, null, "verified directly");
        return row;
    }

    @Transactional
    public void deleteDocument(UUID id) {
        ObjectNode d = rows.lock(DOC, id);
        if (Rows.bool(d, "verified")) {
            throw ApiException.conflict("document_verified", "A verified KYB document is retained and cannot be deleted");
        }
        rows.execute("delete from agent_kyc_document where id = ?", id);
        approvals.cancelOpen(DOC_APPROVAL, id, null, "document deleted");
    }

    // ------------------------------------------------------------------ cash management

    @Transactional
    public ObjectNode createCashRequest(JsonNode req) {
        ObjectNode v = rows.columns(CMR, req);
        ObjectNode outlet = rows.find("agent_outlet", Rows.uuid(v, "agent_outlet_id")).orElseThrow(() ->
                ApiException.unprocessable("outlet_not_found", "The outlet does not exist"));
        if (!"ACTIVE".equals(Rows.str(outlet, "status"))) {
            throw ApiException.unprocessable("outlet_not_active", "The outlet is " + Rows.str(outlet, "status"));
        }
        BigDecimal amount = Rows.dec(v, "amount");
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount", "amount must be positive");
        }
        v.put("status", "REQUESTED");
        ObjectNode row = rows.insert(CMR, v);
        requestCashApproval(row, outlet);
        return row;
    }

    /** Opens the workflow approval for the request as it is now (amount and row_version are the fingerprint). */
    private void requestCashApproval(ObjectNode row, ObjectNode outlet) {
        UUID id = Rows.uuid(row, "id");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("agent_outlet_id", Rows.str(row, "agent_outlet_id"));
        payload.put("outlet_code", Rows.str(outlet, "outlet_code"));
        payload.put("outlet_name", Rows.str(outlet, "name"));
        payload.put("request_type", Rows.str(row, "request_type"));
        payload.put("amount", Rows.dec(row, "amount").toPlainString());
        payload.put("currency", Rows.str(row, "currency"));
        payload.put("requested_for", Rows.str(row, "requested_for"));
        payload.put("row_version", Rows.integer(row, "row_version"));
        approvals.request(ApprovalSpec.of(CASH_APPROVAL, id, "approve",
                        Rows.str(row, "request_type").replace('_', ' ').toLowerCase() + " for outlet "
                                + Rows.str(outlet, "outlet_code"), payload)
                        .withAmount(Rows.dec(row, "amount"), Rows.str(row, "currency")),
                "submit:" + id + ":v" + Rows.integer(row, "row_version"));
    }

    /**
     * After the maker changed a requested cash movement: the open workflow approval (for the old content) is
     * withdrawn and a new one opened for the current content. No-op in local mode.
     */
    public void resubmitCash(UUID id) {
        if (!approvals.centralised()) {
            return;
        }
        ObjectNode r = rows.get(CMR, id);
        if (!"REQUESTED".equals(Rows.str(r, "status"))) {
            return;
        }
        approvals.cancelOpen(CASH_APPROVAL, id, null, "changed by the maker");
        requestCashApproval(r, rows.get("agent_outlet", Rows.uuid(r, "agent_outlet_id")));
    }

    /** True while the request is REQUESTED with the submitted content (row_version) unchanged. */
    @Transactional(readOnly = true)
    public boolean cashAwaitsApproval(UUID id, Integer rowVersion) {
        return rows.find(CMR, id)
                .filter(r -> "REQUESTED".equals(Rows.str(r, "status")))
                .filter(r -> rowVersion == null || rowVersion.equals(Rows.integer(r, "row_version")))
                .isPresent();
    }

    public void checkCashUpdate(UUID id) {
        ObjectNode r = rows.get(CMR, id);
        if (!"REQUESTED".equals(Rows.str(r, "status"))) {
            throw ApiException.conflict("invalid_transition", "Only a requested cash movement can be changed");
        }
    }

    @Transactional
    public ObjectNode approveCash(UUID id) {
        ObjectNode r = rows.lock(CMR, id);
        Hierarchy.requireRequestTransition(Rows.str(r, "status"), "APPROVED");
        if (TenantContext.principal().equals(Rows.str(r, "created_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "The request must be approved by a different user than its maker");
        }
        ObjectNode v = rows.object();
        v.put("status", "APPROVED");
        v.put("approved_by", TenantContext.principal());
        ObjectNode row = rows.update(CMR, id, v);
        approvals.cancelOpen(CASH_APPROVAL, id, null, "approved directly");
        return row;
    }

    @Transactional
    public ObjectNode completeCash(UUID id, String citReference) {
        ObjectNode r = rows.lock(CMR, id);
        if (!"APPROVED".equals(Rows.str(r, "status"))) {
            throw ApiException.conflict("invalid_transition", "Only an approved cash request can be completed");
        }
        ObjectNode v = rows.object();
        v.put("status", "COMPLETED");
        if (citReference != null) {
            v.put("cit_reference", citReference);
        }
        rows.update(CMR, id, v);
        rows.execute("update cash_management_request set completed_at = now() where id = ?", id);
        ObjectNode row = rows.get(CMR, id);
        events.publish("wallets.cash_management_request.completed", CMR, row);
        return row;
    }

    @Transactional
    public ObjectNode cancelCash(UUID id, String reason) {
        ObjectNode r = rows.lock(CMR, id);
        Hierarchy.requireRequestTransition(Rows.str(r, "status"), "CANCELLED");
        ObjectNode v = rows.object();
        v.put("status", "CANCELLED");
        v.put("cancel_reason", reason == null ? "Cancelled" : reason);
        ObjectNode row = rows.update(CMR, id, v);
        approvals.cancelOpen(CASH_APPROVAL, id, null, "cancelled");
        return row;
    }
}
