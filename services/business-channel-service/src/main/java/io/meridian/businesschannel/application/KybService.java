package io.meridian.businesschannel.application;

import static io.meridian.businesschannel.application.CurrentUser.business;

import io.meridian.businesschannel.application.Downstream.Query;
import io.meridian.common.error.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * KYB of the business through the channel ({@code /v1/business/onboarding}, ADMIN users): the portal never calls
 * onboarding-service, document-service or customer-service directly.
 *
 * <ul>
 *   <li>Applications are started on the bank's PUBLISHED non-individual journeys open to the WEB channel, for the
 *       business of the token only ({@code customer_id}, legal entity from the customer record); the body is rebuilt
 *       here.</li>
 *   <li>Ownership: an application is the business's when its {@code customer_id} is the business (404 otherwise,
 *       audited); a document is the application's when document-service records it with owner
 *       {@code ONBOARDING_APPLICATION} and that application; steps must exist on the application.</li>
 *   <li>Risk scoring, automated decisions, reviewer and device / network data are never returned.</li>
 *   <li>Directors, shareholders and UBOs are declared as step input (e.g. a UBO_DECLARATION step); the parties the bank
 *       holds for the business are readable from customer-service relationships.</li>
 * </ul>
 */
@Service
public class KybService {
    static final String ONBOARDING = "onboarding-service";
    static final String DOCUMENTS = "document-service";
    static final String DOCUMENT_OWNER = "ONBOARDING_APPLICATION";
    /** Fields of an application never shown to the business. */
    static final List<String> HIDDEN = List.of("risk_score", "risk_level", "decision", "decision_reasons", "risk_factors",
            "reviewer_id", "reviewed_by", "review_comment", "staff_user_id", "device_fingerprint", "ip_address",
            "geo_location", "utm", "agent_id", "branch_id", "submitted_by");
    static final Set<String> OPEN = Set.of("STARTED", "IN_PROGRESS");
    /** Document types a business may upload for KYB (document-service PlatformDocumentType values). */
    static final Set<String> DOCUMENT_TYPES = Set.of("CORPORATE_DOCUMENT", "CERTIFICATE", "ID_CARD", "PASSPORT",
            "DRIVING_LICENSE", "RESIDENCE_PERMIT", "PROOF_OF_ADDRESS", "BANK_STATEMENT", "OTHER");

    private final Downstream down;
    private final Access access;
    private final StepUp stepUp;
    private final ActionAudit audit;
    private final CurrentUser current;

    public KybService(Downstream down, Access access, StepUp stepUp, ActionAudit audit, CurrentUser current) {
        this.down = down;
        this.access = access;
        this.stepUp = stepUp;
        this.audit = audit;
        this.current = current;
    }

    public JsonNode journeys() {
        access.requireAdmin("businessOnboardingJourneys", "ONBOARDING", null);
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode j : down.all(ONBOARDING, "/v1/onboarding/journeys", Query.of("status", "PUBLISHED"), 200)) {
            if (businessJourney(j)) {
                ObjectNode v = (ObjectNode) j.deepCopy();
                v.remove(List.of("decision_rules", "approval_request_id", "published_by"));
                out.add(v);
            }
        }
        return down.page(out);
    }

    public JsonNode applications(Integer limit, String cursor) {
        access.requireAdmin("businessOnboardingApplications", "ONBOARDING", null);
        ObjectNode page = down.page(down.get(ONBOARDING, "/v1/onboarding/applications",
                Query.of("customer_id", business()).paging(limit, cursor)), r -> Access.ofBusiness(r, "customer_id"));
        ArrayNode data = (ArrayNode) page.get("data");
        for (int i = 0; i < data.size(); i++) {
            data.set(i, view(data.get(i)));
        }
        return page;
    }

    public JsonNode application(UUID id) {
        return view(owned(id, "businessOnboardingGetApplicationsById"));
    }

    public JsonNode start(JsonNode req, String key) {
        String action = "businessOnboardingAddApplications";
        access.requireAdmin(action, "ONBOARDING", null);
        UUID journeyId = Req.uuid(req, "onboarding_journey_id", true);
        JsonNode journey = access.fetch(ONBOARDING, "/v1/onboarding/journeys/" + journeyId, "JOURNEY", journeyId);
        if (!"PUBLISHED".equals(journey.path("status").asString("")) || !businessJourney(journey)) {
            throw ApiException.unprocessable("journey_not_available",
                    "Business applications start on a published business journey open to the web channel");
        }
        JsonNode customer = current.businessCustomer(business());
        ObjectNode body = down.json().createObjectNode()
                .put("onboarding_journey_id", journeyId.toString())
                .put("customer_id", business().toString())
                .put("channel", "WEB");
        String entity = customer.path("legal_entity_id").asString(null);
        if (entity != null) {
            body.put("legal_entity_id", entity);
        }
        String email = CurrentUser.user().path("email").asString(null);
        if (email != null) {
            body.put("email", email);
        }
        JsonNode products = req.get("product_ids");
        if (products != null && products.isArray() && !products.isEmpty()) {
            ArrayNode p = body.putArray("product_ids");
            for (JsonNode x : products) {
                try {
                    p.add(UUID.fromString(x.asString()).toString());
                } catch (IllegalArgumentException e) {
                    throw ApiException.badRequest("invalid_value", "product_ids must be UUIDs");
                }
            }
        }
        stepUp.require(action, "ONBOARDING", journeyId);
        JsonNode created = down.post(ONBOARDING, "/v1/onboarding/applications", body, key, "start");
        audit.succeeded(action, "ONBOARDING", journeyId, created.path("id").asString(null));
        return view(created);
    }

    public JsonNode steps(UUID id, Integer limit, String cursor) {
        owned(id, "businessOnboardingApplicationsSteps");
        return down.page(down.get(ONBOARDING, "/v1/onboarding/applications/" + id + "/steps",
                Query.none().paging(limit, cursor)), r -> id.toString().equals(r.path("onboarding_application_id").asString(null)));
    }

    public JsonNode nextStep(UUID id) {
        owned(id, "businessOnboardingApplicationsNextStep");
        return down.get(ONBOARDING, "/v1/onboarding/applications/" + id + "/next-step");
    }

    public JsonNode submitStep(UUID id, String stepCode, JsonNode req, String key) {
        String action = "businessOnboardingAddApplicationsSteps";
        JsonNode app = owned(id, action);
        requireOpen(app);
        requireStep(id, stepCode);
        JsonNode input = req.get("input");
        if (input == null || !input.isObject()) {
            throw ApiException.unprocessable("invalid_input", "input must be an object of the step's fields");
        }
        JsonNode r = down.post(ONBOARDING, "/v1/onboarding/applications/" + id + "/steps/" + stepCode,
                down.json().createObjectNode().set("input", input), key, "step:" + stepCode);
        audit.succeeded(action, "ONBOARDING", id, null);
        return r;
    }

    public JsonNode documents(UUID id, Integer limit, String cursor) {
        owned(id, "businessOnboardingApplicationsDocuments");
        return down.page(down.get(DOCUMENTS, "/v1/documents", Query.of("owner_type", DOCUMENT_OWNER).with("owner_id", id)
                .paging(limit, cursor)), r -> ownsDocument(id, r));
    }

    public JsonNode addDocument(UUID id, JsonNode req, String key) {
        String action = "businessOnboardingAddApplicationsDocuments";
        JsonNode app = owned(id, action);
        requireOpen(app);
        String step = Req.required(req, "step_code");
        requireStep(id, step);
        String name = Req.required(req, "file_name");
        String type = Req.required(req, "document_type");
        if (!DOCUMENT_TYPES.contains(type)) {
            throw ApiException.unprocessable("invalid_document_type", "document_type must be one of " + DOCUMENT_TYPES);
        }
        ObjectNode body = down.json().createObjectNode()
                .put("owner_type", DOCUMENT_OWNER)
                .put("owner_id", id.toString())
                .put("document_type", type)
                .put("file_name", name.length() > 255 ? name.substring(0, 255) : name)
                .put("content_type", Req.required(req, "content_type").toLowerCase(java.util.Locale.ROOT))
                .put("uploaded_via", "WEB");
        body.putObject("metadata").put("step_code", step)
                .put("application_number", app.path("application_number").asString(""))
                .put("business_customer_id", business().toString());
        JsonNode doc = down.post(DOCUMENTS, "/v1/documents", body, key, "document");
        String documentId = doc.path("id").asString();
        JsonNode upload = down.post(DOCUMENTS, "/v1/documents/" + documentId + "/upload-url", null, key, "upload-url");
        ObjectNode out = down.json().createObjectNode().put("document_id", documentId).put("step_code", step);
        if (upload != null && upload.isObject()) {
            upload.properties().forEach(e -> out.set(e.getKey(), e.getValue()));
        }
        audit.succeeded(action, "ONBOARDING", id, documentId);
        return out;
    }

    public JsonNode completeDocument(UUID id, UUID documentId, JsonNode req, String key) {
        String action = "businessOnboardingApplicationsDocumentsComplete";
        owned(id, action);
        access.owned(DOCUMENTS, "/v1/documents/" + documentId, "DOCUMENT", documentId, action, r -> ownsDocument(id, r));
        JsonNode doc = down.post(DOCUMENTS, "/v1/documents/" + documentId + "/complete-upload", down.json().createObjectNode()
                .put("size_bytes", req.path("size_bytes").asLong()).put("checksum", Req.required(req, "checksum")),
                key, "complete");
        ObjectNode out = down.json().createObjectNode().put("document_id", documentId.toString());
        for (String f : List.of("status", "size_bytes", "checksum", "document_type", "file_name")) {
            if (doc != null && doc.hasNonNull(f)) {
                out.set(f, doc.get(f));
            }
        }
        audit.succeeded(action, "ONBOARDING", id, documentId);
        return out;
    }

    public JsonNode submit(UUID id, String key) {
        String action = "businessOnboardingApplicationsSubmit";
        JsonNode app = owned(id, action);
        requireOpen(app);
        stepUp.require(action, "ONBOARDING", id);
        JsonNode r = down.post(ONBOARDING, "/v1/onboarding/applications/" + id + "/submit", null, key, "submit");
        audit.succeeded(action, "ONBOARDING", id, null);
        return view(r);
    }

    public JsonNode relatedParties(Integer limit, String cursor) {
        access.requireAdmin("businessOnboardingRelatedParties", "ONBOARDING", null);
        return down.page(down.get("customer-service", "/v1/customers/" + business() + "/relationships",
                        Query.none().paging(limit, cursor)),
                r -> Access.ofBusiness(r, "customer_id") || Access.ofBusiness(r, "related_customer_id"));
    }

    // ------------------------------------------------------------------ helpers

    /** An application of the business (ADMIN); another business's or a missing one: 404, audited. */
    private JsonNode owned(UUID id, String action) {
        access.requireAdmin(action, "ONBOARDING", id);
        return access.owned(ONBOARDING, "/v1/onboarding/applications/" + id, "APPLICATION", id, action,
                r -> Access.ofBusiness(r, "customer_id"));
    }

    private void requireStep(UUID id, String stepCode) {
        boolean exists = false;
        for (JsonNode s : down.all(ONBOARDING, "/v1/onboarding/applications/" + id + "/steps", Query.none(), 200)) {
            if (stepCode.equals(s.path("step_code").asString(null))) {
                exists = true;
                break;
            }
        }
        if (!exists) {
            throw ApiException.unprocessable("unknown_step", "The application has no step " + stepCode);
        }
    }

    private static void requireOpen(JsonNode app) {
        if (!OPEN.contains(app.path("status").asString(""))) {
            throw ApiException.conflict("application_closed", "The application is " + app.path("status").asString());
        }
    }

    private static boolean ownsDocument(UUID applicationId, JsonNode doc) {
        return DOCUMENT_OWNER.equals(doc.path("owner_type").asString(null))
                && applicationId.toString().equals(doc.path("owner_id").asString(null));
    }

    private static boolean businessJourney(JsonNode j) {
        if ("INDIVIDUAL".equals(j.path("customer_type").asString("INDIVIDUAL"))) {
            return false;
        }
        for (JsonNode c : j.path("channels")) {
            if ("WEB".equals(c.asString())) {
                return true;
            }
        }
        return false;
    }

    /** The application as the business sees it (no risk, decision, review or device data). */
    static JsonNode view(JsonNode app) {
        if (!(app instanceof ObjectNode o)) {
            return app;
        }
        ObjectNode v = o.deepCopy();
        v.remove(HIDDEN);
        return v;
    }
}
