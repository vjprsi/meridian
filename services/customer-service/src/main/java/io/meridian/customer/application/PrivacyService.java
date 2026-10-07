package io.meridian.customer.application;

import static io.meridian.customer.application.Json.now;
import static io.meridian.customer.application.Json.text;
import static io.meridian.customer.application.Json.truncate;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.customer.api.generated.model.Consent;
import io.meridian.customer.api.generated.model.Customer;
import io.meridian.customer.api.generated.model.CustomerAddress;
import io.meridian.customer.api.generated.model.CustomerContact;
import io.meridian.customer.api.generated.model.CustomerDevice;
import io.meridian.customer.api.generated.model.CustomerIdentifier;
import io.meridian.customer.api.generated.model.CustomerKycTierHistory;
import io.meridian.customer.api.generated.model.CustomerRelationship;
import io.meridian.customer.api.generated.model.PrivacyRequest;
import io.meridian.customer.domain.Lifecycles;
import io.meridian.customer.infrastructure.Crypto;
import io.meridian.customer.infrastructure.DataSubjectFanout;
import io.meridian.customer.infrastructure.ExternalServices;
import io.meridian.customer.infrastructure.Rows;
import io.meridian.web.crud.CrudEngine;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Data subject requests. Flow: RECEIVED -> (verify-identity) IN_PROGRESS -> (execute) COMPLETED or
 * PARTIALLY_COMPLETED, or REJECTED with a reason. Execution:
 * <ul>
 *   <li>ACCESS / EXPORT compile all customer data (secrets excluded) into a JSON package stored as a restricted
 *       document in document-service, including the customer's rows in every service listed in
 *       {@code meridian.privacy.services} (their internal data-lifecycle export; GAP-08). If one of them cannot answer
 *       the request fails (502) and stays IN_PROGRESS, so an incomplete package is never delivered;</li>
 *   <li>ERASURE anonymises personal data where lawful, here and in every listed service. An active legal hold
 *       (archive-service) blocks all erasure; an open relationship (contract) blocks erasure of the identity record and
 *       everything elsewhere; within the AML record-keeping period (5 years after the relationship ends) identity data
 *       is kept here and the services keep their retained records ({@code within_retention}); append-only records
 *       are always kept. What is kept, and every service that could not be reached, is listed in retention_blockers
 *       and the request completes partially (erasure is idempotent: a new request re-runs it);</li>
 *   <li>OBJECTION / RESTRICTION stop marketing and profiling (consents withdrawn, marketing flags off);</li>
 *   <li>RECTIFICATION is applied by the handler through the normal update APIs and then confirmed here.</li>
 * </ul>
 */
@Service
public class PrivacyService {
    static final String T = "privacy_request";
    private static final int AML_RETENTION_YEARS = 5;
    private static final Set<String> OPEN = Set.of("RECEIVED", "VERIFYING_IDENTITY", "IN_PROGRESS");
    private static final String ERASED = "ERASED";

    private final Rows rows;
    private final CrudEngine crud;
    private final Guards guards;
    private final Events events;
    private final ConsentService consents;
    private final ExternalServices external;
    private final JsonMapper json;
    private final ContactService contacts;
    private final DataSubjectFanout fanout;

    public PrivacyService(Rows rows, CrudEngine crud, Guards guards, Events events, ConsentService consents,
                          ExternalServices external, JsonMapper json, ContactService contacts, DataSubjectFanout fanout) {
        this.rows = rows;
        this.fanout = fanout;
        this.contacts = contacts;
        this.crud = crud;
        this.guards = guards;
        this.events = events;
        this.consents = consents;
        this.external = external;
        this.json = json;
    }

    @Transactional
    public ObjectNode create(Object body) {
        ObjectNode v = crud.toColumns(T, body);
        guards.existingCustomer(UUID.fromString(text(v, "customer_id")));
        String status = text(v, "status");
        if (status != null && !"RECEIVED".equals(status)) {
            throw ApiException.unprocessable("invalid_initial_status", "A privacy request is created as RECEIVED");
        }
        v.put("status", "RECEIVED");
        OffsetDateTime received = text(v, "received_at") == null ? now() : Json.timestamp(v, "received_at");
        OffsetDateTime due = Json.timestamp(v, "due_at");
        if (!due.isAfter(received)) {
            throw ApiException.unprocessable("invalid_due_at", "due_at must be after received_at");
        }
        if (due.isAfter(received.plusDays(90))) {
            throw ApiException.unprocessable("invalid_due_at", "The statutory deadline cannot exceed 90 days");
        }
        v.remove("retention_blockers");
        return rows.insert(T, v);
    }

    @Transactional
    public ObjectNode update(UUID id, String ifMatch, Object body) {
        int expected = CustomerService.parseVersion(ifMatch);
        ObjectNode current = rows.lock(T, id);
        if (!OPEN.contains(text(current, "status"))) {
            throw ApiException.conflict("request_closed", "A " + text(current, "status") + " request cannot be changed");
        }
        ObjectNode v = crud.toColumns(T, body);
        String status = text(v, "status");
        if (status != null && !status.equals(text(current, "status"))) {
            if (!("VERIFYING_IDENTITY".equals(status) && "RECEIVED".equals(text(current, "status")))) {
                throw ApiException.unprocessable("status_change_not_allowed", "Use verify-identity, execute or reject");
            }
        }
        for (String f : new String[] {"customer_id", "request_type", "rejection_reason", "retention_blockers"}) {
            if (v.has(f) && !"rejection_reason".equals(f) && !text(v, f).equals(text(current, f))) {
                throw ApiException.unprocessable("immutable_field", f + " cannot be changed");
            }
            v.remove(f);
        }
        return rows.update(T, id, v, expected).orElseThrow(() -> CustomerService.versionConflict(expected));
    }

    @Transactional
    public ObjectNode verifyIdentity(UUID id, String method) {
        ObjectNode r = rows.lock(T, id);
        String to = Lifecycles.PRIVACY_REQUEST.apply("verify-identity", text(r, "status"));
        ObjectNode v = rows.object();
        v.put("status", to);
        v.put("details", truncate(appendNote(text(r, "details"), "Identity verified by " + TenantContext.principal()
                + " via " + method), 10_000));
        return rows.update(T, id, v);
    }

    @Transactional
    public ObjectNode reject(UUID id, String reason) {
        ObjectNode r = rows.lock(T, id);
        String to = Lifecycles.PRIVACY_REQUEST.apply("reject", text(r, "status"));
        ObjectNode v = rows.object();
        v.put("status", to);
        v.put("rejection_reason", truncate(reason, 255));
        v.put("completed_at", now().toString());
        ObjectNode row = rows.update(T, id, v);
        events.publish("party.privacy_request.rejected", T, row, PrivacyRequest.class);
        return row;
    }

    @Transactional
    public ObjectNode execute(UUID id) {
        ObjectNode r = rows.lock(T, id);
        Lifecycles.PRIVACY_REQUEST.apply("execute", text(r, "status"));
        UUID customerId = UUID.fromString(text(r, "customer_id"));
        ObjectNode customer = rows.lock("customer", customerId);
        ObjectNode v = rows.object();
        String outcome = "COMPLETED";
        switch (text(r, "request_type")) {
            case "ACCESS", "EXPORT" -> {
                ObjectNode export = compileExport(customer);
                export.set("services", exportElsewhere(customerId, id));
                byte[] content = json.writeValueAsBytes(export);
                Map<String, Object> sections = new LinkedHashMap<>();
                export.properties().forEach(e -> sections.put(e.getKey(), e.getValue().isArray() ? e.getValue().size() : 1));
                UUID documentId = external.storeExport(customerId, id, content, Crypto.sha256Hex(content), sections);
                v.put("result_document_id", documentId.toString());
            }
            case "ERASURE" -> {
                ArrayNode blockers = erase(customer, id);
                if (!blockers.isEmpty()) {
                    outcome = "PARTIALLY_COMPLETED";
                    v.set("retention_blockers", blockers);
                }
            }
            case "OBJECTION" -> stopMarketing(customerId, Set.of("MARKETING", "PROFILING"), "Objection " + id);
            case "RESTRICTION" -> stopMarketing(customerId,
                    Set.of("MARKETING", "PROFILING", "ANALYTICS", "DATA_SHARING_THIRD_PARTY", "LOCATION"), "Restriction " + id);
            default -> { } // RECTIFICATION: corrections are made through the customer APIs before execution
        }
        v.put("status", outcome);
        v.put("completed_at", now().toString());
        ObjectNode row = rows.update(T, id, v);
        events.publish("party.privacy_request.completed", T, row, PrivacyRequest.class);
        return row;
    }

    // ------------------------------------------------------------------ export

    private ObjectNode compileExport(ObjectNode customer) {
        UUID id = UUID.fromString(text(customer, "id"));
        ObjectNode e = json.createObjectNode();
        e.put("generated_at", now().toString());
        e.set("customer", json.valueToTree(rows.read(customer, Customer.class)));
        e.set("identifiers", list("customer_identifier", id, CustomerIdentifier.class));
        e.set("addresses", list("customer_address", id, CustomerAddress.class));
        e.set("contacts", list("customer_contact", id, CustomerContact.class));
        e.set("devices", list("customer_device", id, CustomerDevice.class));
        e.set("consents", list("consent", id, Consent.class));
        e.set("kyc_tier_history", list("customer_kyc_tier_history", id, CustomerKycTierHistory.class));
        ArrayNode rel = json.createArrayNode();
        rows.query("select row_to_json(t)::text from customer_relationship t where t.customer_id = ? or t.related_customer_id = ? order by t.id",
                id, id).forEach(x -> rel.add(json.valueToTree(rows.read(x, CustomerRelationship.class))));
        e.set("relationships", rel);
        e.set("credentials", json.valueToTree(rows.query("""
                select json_build_object('credential_type', credential_type, 'status', status, 'created_at', created_at,
                    'last_used_at', last_used_at)::text
                from customer_credential where customer_id = ? order by id""", id)));
        return e;
    }

    /** The customer's rows in the other services (fails when one of them cannot answer). */
    private ObjectNode exportElsewhere(UUID customerId, UUID requestId) {
        ObjectNode out = json.createObjectNode();
        for (DataSubjectFanout.Outcome o : fanout.export(customerId, requestId)) {
            if (o.failed()) {
                throw new ApiException(502, "export_incomplete", o.service() + " could not export its data (" + o.error()
                        + "); the request stays IN_PROGRESS, execute it again");
            }
            out.set(o.service(), o.body() == null ? json.createObjectNode() : o.body().path("tables"));
        }
        return out;
    }

    private ArrayNode list(String table, UUID customerId, Class<?> model) {
        ArrayNode out = json.createArrayNode();
        rows.query("select row_to_json(t)::text from " + table + " t where t.customer_id = ? order by t.id", customerId)
                .forEach(x -> out.add(json.valueToTree(rows.read(x, model))));
        return out;
    }

    // ------------------------------------------------------------------ erasure

    private ArrayNode erase(ObjectNode customer, UUID requestId) {
        UUID id = UUID.fromString(text(customer, "id"));
        String status = text(customer, "status");
        ArrayNode blockers = json.createArrayNode();
        // Always lawful: consent-based processing (marketing, profiling, analytics) stops.
        stopMarketing(id, Set.of("MARKETING", "PROFILING", "ANALYTICS", "LOCATION", "DATA_SHARING_THIRD_PARTY"), "Erasure request");
        // A legal hold (litigation, investigation, regulator) preserves everything, here and in every other service.
        tools.jackson.databind.JsonNode holds = fanout.legalHolds(id);
        if (holds != null && holds.path("on_hold").asBoolean(false)) {
            List<String> names = new java.util.ArrayList<>();
            holds.path("holds").forEach(h -> names.add(h.path("name").asString(h.path("legal_hold_id").asString(""))));
            blockers.add(blocker("all_personal_data", "LEGAL_HOLD",
                    "Legal hold " + String.join(", ", names) + " covers this customer; nothing is erased until it is released", null));
            return blockers;
        }
        boolean onboarded = customer.get("onboarded_at") != null && !customer.get("onboarded_at").isNull();
        if (Lifecycles.CUSTOMER_OPEN.contains(status) && !"PROSPECT".equals(status) && !"PENDING_KYC".equals(status)) {
            blockers.add(blocker("customer_profile", "CONTRACT",
                    "The customer relationship is open; identity, contact, address and channel data are needed to perform the contract", null));
            return blockers;
        }
        // The relationship has ended (or never started): channel access data goes.
        rows.execute("""
                update customer_device set status = 'REVOKED', revoked_at = coalesce(revoked_at, now()), revocation_reason = 'Erasure request',
                    push_token = null, public_key = null, model = null, name = null, os_version = null, app_version = null,
                    last_ip = null, device_fingerprint = 'ERASED-' || id::text
                where customer_id = ?""", id);
        rows.execute("""
                update customer_credential set status = 'REVOKED', secret_hash = null, public_key = null,
                    webauthn_credential_id = null where customer_id = ?""", id);
        OffsetDateTime ended = Json.timestamp(customer, "closed_at");
        OffsetDateTime retainUntil = (ended == null ? now() : ended).plusYears(AML_RETENTION_YEARS);
        boolean withinRetention = onboarded && retainUntil.isAfter(now());
        // The other services: anonymise what may go, report what they must keep (and who could not be reached).
        eraseElsewhere(id, requestId, withinRetention, retainUntil, blockers);
        if (onboarded) {
            if (withinRetention) {
                blockers.add(blocker("identity_and_kyc", "LEGAL_OBLIGATION",
                        "AML/CFT record keeping: identification data is retained for " + AML_RETENTION_YEARS
                                + " years after the relationship ends", retainUntil.toLocalDate().toString()));
                // Contact points are not part of the AML record and can go.
                contacts.removeAllFromSearch(id);
                rows.execute("delete from contact_verification where customer_id = ?", id);
                rows.execute("delete from customer_contact where customer_id = ?", id);
                return blockers;
            }
        }
        // Full anonymisation.
        contacts.removeAllFromSearch(id);
        rows.execute("delete from contact_verification where customer_id = ?", id);
        rows.execute("delete from customer_contact where customer_id = ?", id);
        rows.execute("delete from customer_address where customer_id = ?", id);
        rows.execute("""
                update customer_identifier set identifier_value = 'ERASED', issuing_authority = null, issue_date = null,
                    value_hash = encode(sha256(convert_to(id::text || random()::text, 'UTF8')), 'hex')
                where customer_id = ?""", id);
        ObjectNode v = rows.object();
        v.put("display_name", ERASED);
        for (String f : List.of("title", "first_name", "middle_name", "last_name", "name_local_script", "date_of_birth",
                "place_of_birth", "legal_name", "deceased_on", "employer_name", "occupation", "external_ref",
                "custom_field_1", "custom_field_2", "custom_field_3", "custom_field_4", "custom_field_5")) {
            v.putNull(f);
        }
        v.putNull("metadata");
        v.put("erased_at", now().toString());
        if (!"CLOSED".equals(status) && !"DECEASED".equals(status)) {
            v.put("status", "CLOSED");
            v.put("closed_at", now().toString());
            v.put("closure_reason", "ERASED_ON_REQUEST");
            v.put("status_reason", "Erased on data subject request");
            v.put("status_changed_at", now().toString());
            v.put("status_changed_by", truncate(TenantContext.principal(), 64));
        }
        ObjectNode row = rows.update("customer", id, v);
        events.publish("party.customer.updated", "customer", row, Customer.class);
        return blockers;
    }

    private void eraseElsewhere(UUID customerId, UUID requestId, boolean withinRetention, OffsetDateTime retainUntil,
                                ArrayNode blockers) {
        for (DataSubjectFanout.Outcome o : fanout.erase(customerId, requestId, withinRetention)) {
            if (o.failed()) {
                blockers.add(blocker(o.service(), "NOT_EXECUTED", o.service() + " could not be reached (" + o.error()
                        + "); submit a new erasure request to complete it", null));
                continue;
            }
            if (o.body() == null) {
                continue;
            }
            for (tools.jackson.databind.JsonNode t : o.body().path("tables")) {
                if ("RETAINED".equals(t.path("action").asString()) && t.path("records").asLong(0) > 0) {
                    boolean appendOnly = t.path("reason").asString("").startsWith("Append-only");
                    blockers.add(blocker(o.service() + "/" + t.path("table").asString(),
                            t.path("lawful_basis").asString("LEGAL_OBLIGATION"), t.path("reason").asString(""),
                            appendOnly || !withinRetention ? null : retainUntil.toLocalDate().toString()));
                }
            }
        }
    }

    private void stopMarketing(UUID customerId, Set<String> purposes, String reason) {
        consents.withdrawPurposes(customerId, purposes, reason);
        rows.execute("update customer_contact set can_receive_marketing = false where customer_id = ? and can_receive_marketing", customerId);
    }

    private ObjectNode blocker(String category, String basis, String reason, String until) {
        ObjectNode b = json.createObjectNode();
        b.put("category", category);
        b.put("lawful_basis", basis);
        b.put("reason", reason);
        if (until != null) {
            b.put("retain_until", until);
        }
        return b;
    }

    private static String appendNote(String details, String note) {
        return details == null || details.isBlank() ? note : details + "\n" + note;
    }
}
