package io.meridian.customerchannel.application;

import static io.meridian.customerchannel.application.AccountService.uuid;
import static io.meridian.customerchannel.application.Ownership.me;
import static io.meridian.customerchannel.application.Ownership.mine;
import static io.meridian.customerchannel.application.ProfileService.required;
import static io.meridian.customerchannel.application.ProfileService.text;

import io.meridian.common.error.ApiException;
import io.meridian.customerchannel.application.Downstream.Query;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Documents the customer uploads (owner = the customer) and their KYC status and evidence. */
@Service
public class DocumentService {
    private static final String DOCS = "document-service";
    private static final String KYC = "kyc-service";

    private final Downstream down;
    private final Ownership own;
    private final ActionAudit audit;

    public DocumentService(Downstream down, Ownership own, ActionAudit audit) {
        this.down = down;
        this.own = own;
        this.audit = audit;
    }

    private static boolean isMyDocument(JsonNode r) {
        return "CUSTOMER".equals(r.path("owner_type").asString(null)) && mine(r, "owner_id");
    }

    public JsonNode documents(Integer limit, String cursor) {
        return down.page(down.get(DOCS, "/v1/documents", Query.of("owner_type", "CUSTOMER").with("owner_id", me())
                .paging(limit, cursor)), DocumentService::isMyDocument);
    }

    public JsonNode create(JsonNode req, String key) {
        ObjectNode body = down.json().createObjectNode()
                .put("owner_type", "CUSTOMER")
                .put("owner_id", me().toString())
                .put("document_type", required(req, "document_type"))
                .put("file_name", required(req, "file_name"))
                .put("content_type", required(req, "content_type"))
                .put("classification", "CONFIDENTIAL")
                .put("issuing_country", text(req, "issuing_country"))
                .put("document_number", text(req, "document_number"))
                .put("uploaded_via", "WEB");
        date(body, req, "issued_on");
        date(body, req, "expires_on");
        JsonNode created = down.post(DOCS, "/v1/documents", body, key, "create");
        audit.succeeded("myDocumentsCreate", "DOCUMENT", null, created.path("id").asString(null));
        return created;
    }

    public JsonNode uploadUrl(UUID id, String key) {
        own.document(id, "myDocumentsUploadUrl");
        return down.post(DOCS, "/v1/documents/" + id + "/upload-url", null, key, "upload-url");
    }

    public JsonNode completeUpload(UUID id, JsonNode req, String key) {
        own.document(id, "myDocumentsCompleteUpload");
        long size = req.path("size_bytes").asLong(-1);
        String checksum = required(req, "checksum");
        if (size < 0 || !checksum.matches("[0-9a-fA-F]{64}")) {
            throw ApiException.unprocessable("invalid_upload", "size_bytes and a SHA-256 checksum (hex) are required");
        }
        JsonNode r = down.post(DOCS, "/v1/documents/" + id + "/complete-upload", down.json().createObjectNode()
                .put("size_bytes", size).put("checksum", checksum.toLowerCase()), key, "complete");
        audit.succeeded("myDocumentsCompleteUpload", "DOCUMENT", id, null);
        return r;
    }

    // ------------------------------------------------------------------ KYC

    /** The customer's KYC profile; 404 {@code kyc_profile_not_found} when there is none yet. */
    public JsonNode kycProfile() {
        for (JsonNode p : down.get(KYC, "/v1/kyc/profiles", Query.of("customer_id", me()).with("limit", 5)).path("data")) {
            if (mine(p, "customer_id")) {
                return p;
            }
        }
        throw new ApiException(404, "kyc_profile_not_found", "No KYC profile exists for you yet");
    }

    public JsonNode kycDocuments(Integer limit, String cursor) {
        return down.page(down.get(KYC, "/v1/kyc/documents", Query.of("customer_id", me()).paging(limit, cursor)),
                r -> mine(r, "customer_id"));
    }

    /** Submits uploaded documents of the customer as KYC evidence on the customer's own profile. */
    public JsonNode submitKycDocument(JsonNode req, String key) {
        String action = "myKycAddDocuments";
        JsonNode profile = kycProfile();
        UUID front = uuid(req, "document_id", true);
        own.document(front, action);
        UUID back = uuid(req, "back_document_id", false);
        if (back != null) {
            own.document(back, action);
        }
        ObjectNode body = down.json().createObjectNode()
                .put("kyc_profile_id", profile.path("id").asString())
                .put("customer_id", me().toString())
                .put("document_type", required(req, "document_type"))
                .put("document_id", front.toString())
                .put("issuing_country", text(req, "issuing_country"))
                .put("document_number", text(req, "document_number"))
                .put("captured_channel", "WEB");
        if (back != null) {
            body.put("back_document_id", back.toString());
        }
        date(body, req, "issue_date");
        date(body, req, "expiry_date");
        JsonNode created = down.post(KYC, "/v1/kyc/documents", body, key, "create");
        audit.succeeded(action, "DOCUMENT", front, created.path("id").asString(null));
        return created;
    }

    private static void date(ObjectNode body, JsonNode req, String field) {
        String v = text(req, field);
        if (v != null) {
            body.put(field, LocalDate.parse(v).toString());
        }
    }
}
