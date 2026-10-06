package io.meridian.kyc.application;

import static io.meridian.kyc.application.KycSupport.date;
import static io.meridian.kyc.application.KycSupport.text;
import static io.meridian.kyc.application.KycSupport.uuid;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.kyc.api.generated.model.KycDocument;
import io.meridian.kyc.domain.KycRules;
import io.meridian.kyc.infrastructure.Rows;
import io.meridian.web.crud.CrudEngine;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

/** KYC documents: capture, expiry notice, maker-checker review. */
@Service
public class DocumentService {
    static final String TABLE = "kyc_document";
    /** Notice period for party.kyc_document.expiring. */
    static final int EXPIRY_NOTICE_DAYS = 30;
    /** Approval resource type for document reviews in workflow-service (G11). */
    public static final String APPROVAL_RESOURCE = "kyc_document";

    private final Rows rows;
    private final CrudEngine crud;
    private final KycSupport kyc;
    private final ProfileService profiles;
    private final Approvals approvals;

    public DocumentService(Rows rows, CrudEngine crud, KycSupport kyc, ProfileService profiles, Approvals approvals) {
        this.approvals = approvals;
        this.rows = rows;
        this.crud = crud;
        this.kyc = kyc;
        this.profiles = profiles;
    }

    @Transactional
    public ObjectNode create(Object body) {
        ObjectNode v = crud.toColumns(TABLE, body);
        LocalDate today = KycSupport.today();
        LocalDate expiry = date(v, "expiry_date");
        LocalDate issued = date(v, "issue_date");
        if (expiry != null && expiry.isBefore(today)) {
            throw ApiException.unprocessable("document_expired", "The document has already expired");
        }
        if (issued != null && issued.isAfter(today)) {
            throw ApiException.unprocessable("invalid_issue_date", "issue_date cannot be in the future");
        }
        if (issued != null && expiry != null && !expiry.isAfter(issued)) {
            throw ApiException.unprocessable("invalid_expiry_date", "expiry_date must be after issue_date");
        }
        if (v.hasNonNull("issuing_country")) {
            v.put("issuing_country", text(v, "issuing_country").toUpperCase(java.util.Locale.ROOT));
        }
        profiles.forCustomer(uuid(v, "kyc_profile_id"), uuid(v, "customer_id"));
        v.put("status", "UPLOADED");
        v.remove("rejection_reason");
        ObjectNode row = rows.insert(TABLE, v);
        if (expiry != null && !expiry.isAfter(today.plusDays(EXPIRY_NOTICE_DAYS))) {
            kyc.publish("party.kyc_document.expiring", TABLE, row, KycDocument.class);
        }
        // Central maker-checker: the upload is the maker's submission; the reviewer decides in the workflow inbox.
        UUID id = uuid(row, "id");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kyc_profile_id", text(row, "kyc_profile_id"));
        payload.put("customer_id", text(row, "customer_id"));
        payload.put("document_type", text(row, "document_type"));
        payload.put("document_id", text(row, "document_id"));
        payload.put("issuing_country", text(row, "issuing_country"));
        payload.put("issue_date", text(row, "issue_date"));
        payload.put("expiry_date", text(row, "expiry_date"));
        approvals.request(ApprovalSpec.of(APPROVAL_RESOURCE, id, "accept",
                "KYC document " + text(row, "document_type") + " for customer " + text(row, "customer_id"), payload),
                "upload:" + id);
        return row;
    }

    @Transactional
    public ObjectNode accept(UUID id) {
        ObjectNode d = rows.lock(TABLE, id);
        KycRules.requireDocumentReviewable(text(d, "status"));
        KycRules.requireFourEyes(text(d, "created_by"), KycSupport.principal(), "document");
        LocalDate expiry = date(d, "expiry_date");
        if (expiry != null && expiry.isBefore(KycSupport.today())) {
            throw ApiException.unprocessable("document_expired", "An expired document cannot be accepted");
        }
        ObjectNode v = rows.object();
        v.put("status", "ACCEPTED");
        ObjectNode updated = rows.update(TABLE, id, v);
        kyc.publish("party.kyc_document.accepted", TABLE, updated, KycDocument.class);
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "accepted directly");
        return updated;
    }

    @Transactional
    public ObjectNode reject(UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.unprocessable("reason_required", "A rejection reason is required");
        }
        ObjectNode d = rows.lock(TABLE, id);
        KycRules.requireDocumentReviewable(text(d, "status"));
        ObjectNode v = rows.object();
        v.put("status", "REJECTED");
        v.put("rejection_reason", reason);
        ObjectNode updated = rows.update(TABLE, id, v);
        kyc.publish("party.kyc_document.rejected", TABLE, updated, KycDocument.class);
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "rejected directly");
        return updated;
    }

    /** True while the document still awaits review and is the one submitted (same document reference). */
    @Transactional(readOnly = true)
    public boolean awaitsReview(UUID id, String documentId) {
        return rows.find(TABLE, id)
                .filter(d -> "UPLOADED".equals(text(d, "status")) || "PROCESSING".equals(text(d, "status")))
                .filter(d -> documentId == null || documentId.equals(text(d, "document_id")))
                .isPresent();
    }
}
