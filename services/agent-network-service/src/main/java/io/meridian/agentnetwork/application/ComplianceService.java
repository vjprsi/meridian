package io.meridian.agentnetwork.application;

import io.meridian.agentnetwork.domain.Hierarchy;
import io.meridian.agentnetwork.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import java.math.BigDecimal;
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

    private final Rows rows;
    private final Events events;

    public ComplianceService(Rows rows, Events events) {
        this.rows = rows;
        this.events = events;
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
        if (rows.scalar("select coalesce(?::date < current_date, false)", Boolean.class, Rows.str(v, "expires_at")).orElse(false)) {
            throw ApiException.unprocessable("document_expired", "The document has already expired");
        }
        v.put("verified", false);
        return rows.insert(DOC, v);
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
        if (rows.scalar("select expires_at is not null and expires_at < current_date from agent_kyc_document where id = ?",
                Boolean.class, id).orElse(false)) {
            throw ApiException.unprocessable("document_expired", "An expired document cannot be verified");
        }
        rows.execute("update agent_kyc_document set verified = true, verified_by = ?, verified_at = now() where id = ?",
                TenantContext.principal(), id);
        ObjectNode row = rows.get(DOC, id);
        events.publish("wallets.agent_kyc_document.verified", DOC, row);
        return row;
    }

    @Transactional
    public void deleteDocument(UUID id) {
        ObjectNode d = rows.lock(DOC, id);
        if (Rows.bool(d, "verified")) {
            throw ApiException.conflict("document_verified", "A verified KYB document is retained and cannot be deleted");
        }
        rows.execute("delete from agent_kyc_document where id = ?", id);
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
        return rows.insert(CMR, v);
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
        return rows.update(CMR, id, v);
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
        return rows.update(CMR, id, v);
    }
}
