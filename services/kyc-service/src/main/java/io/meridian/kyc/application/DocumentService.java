package io.meridian.kyc.application;

import static io.meridian.kyc.application.KycSupport.date;
import static io.meridian.kyc.application.KycSupport.text;
import static io.meridian.kyc.application.KycSupport.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.kyc.api.generated.model.KycDocument;
import io.meridian.kyc.domain.KycRules;
import io.meridian.kyc.infrastructure.Rows;
import io.meridian.web.crud.CrudEngine;
import java.time.LocalDate;
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

    private final Rows rows;
    private final CrudEngine crud;
    private final KycSupport kyc;
    private final ProfileService profiles;

    public DocumentService(Rows rows, CrudEngine crud, KycSupport kyc, ProfileService profiles) {
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
        return updated;
    }
}
