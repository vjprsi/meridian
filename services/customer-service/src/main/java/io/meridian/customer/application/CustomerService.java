package io.meridian.customer.application;

import static io.meridian.customer.application.Json.date;
import static io.meridian.customer.application.Json.now;
import static io.meridian.customer.application.Json.text;
import static io.meridian.customer.application.Json.today;
import static io.meridian.customer.application.Json.truncate;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.common.error.ApiException.FieldError;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.customer.api.generated.model.Customer;
import io.meridian.customer.api.generated.model.CustomerKycTierHistory;
import io.meridian.customer.domain.KycTierPolicy;
import io.meridian.customer.domain.Lifecycles;
import io.meridian.customer.infrastructure.Rows;
import io.meridian.web.crud.CrudEngine;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Customer master data: creation, maintenance, lifecycle, KYC tier (maker-checker) and duplicate merge. */
@Service
public class CustomerService {
    static final String T = "customer";
    static final Set<String> NATURAL_PERSON = Set.of("INDIVIDUAL", "SOLE_TRADER");
    private static final int AGE_OF_MAJORITY = 18;
    /** resource_type of KYC tier upgrade approval requests in workflow-service. */
    public static final String TIER_APPROVAL_RESOURCE = "customer_kyc_tier";
    /** action of KYC tier upgrade approval requests. */
    public static final String TIER_APPROVAL_ACTION = "change_tier";

    private final Rows rows;
    private final CrudEngine crud;
    private final Events events;
    private final JsonMapper json;
    private final Approvals approvals;

    public CustomerService(Rows rows, CrudEngine crud, Events events, JsonMapper json, Approvals approvals) {
        this.rows = rows;
        this.crud = crud;
        this.events = events;
        this.json = json;
        this.approvals = approvals;
    }

    // ------------------------------------------------------------------ create / update

    @Transactional
    public ObjectNode create(Object body) {
        ObjectNode v = crud.toColumns(T, body);
        String status = text(v, "status");
        if (status != null && !Lifecycles.CUSTOMER_INITIAL.contains(status)) {
            throw ApiException.unprocessable("invalid_initial_status",
                    "A customer is created as PROSPECT or PENDING_KYC; use the lifecycle actions afterwards");
        }
        String type = text(v, "customer_type");
        String display = deriveDisplayName(v, type);
        if (display == null) {
            throw new ApiException(422, "invalid_customer", "Customer data is invalid", List.of(NATURAL_PERSON.contains(type)
                    ? new FieldError("last_name", "required", "first_name/last_name or display_name is required")
                    : new FieldError("legal_name", "required", "legal_name, trading_name or display_name is required")));
        }
        v.put("display_name", display);
        validateProfile(v, type);
        if (!NATURAL_PERSON.contains(type) && text(v, "legal_name") == null) {
            v.put("legal_name", display);
        }
        v.put("customer_number", nextCustomerNumber());
        ObjectNode row = rows.insert(T, v);
        events.publish("party.customer.created", T, row, Customer.class);
        return row;
    }

    @Transactional
    public ObjectNode update(UUID id, String ifMatch, Object body) {
        int expected = parseVersion(ifMatch);
        ObjectNode current = rows.lock(T, id);
        if ("CLOSED".equals(text(current, "status"))) {
            throw ApiException.conflict("customer_closed", "A closed customer cannot be changed");
        }
        ObjectNode v = crud.toColumns(T, body);
        String status = text(v, "status");
        if (status != null && !status.equals(text(current, "status"))) {
            throw ApiException.unprocessable("status_change_not_allowed",
                    "Status changes only through activate, suspend, reactivate, close or mark-deceased");
        }
        v.remove("status");
        for (String immutable : List.of("customer_number", "customer_type")) {
            String nv = text(v, immutable);
            if (nv != null && !nv.equals(text(current, immutable))) {
                throw ApiException.unprocessable("immutable_field", immutable + " cannot be changed");
            }
            v.remove(immutable);
        }
        if (v.isEmpty()) {
            if (expected != current.get("row_version").asInt()) {
                throw versionConflict(expected);
            }
            return current;
        }
        ObjectNode merged = current.deepCopy();
        merged.setAll(v);
        validateProfile(merged, text(current, "customer_type"));
        if (v.has("date_of_birth")) {
            v.put("is_minor", merged.get("is_minor").asBoolean());
        }
        ObjectNode row = rows.update(T, id, v, expected).orElseThrow(() -> versionConflict(expected));
        events.publish("party.customer.updated", T, row, Customer.class);
        return row;
    }

    /** display_name as sent, else first (+ middle) + last name for natural persons, trading or legal name otherwise. */
    static String deriveDisplayName(ObjectNode v, String type) {
        String given = text(v, "display_name");
        if (given != null && !given.isBlank()) {
            return given.trim();
        }
        String derived;
        if (NATURAL_PERSON.contains(type)) {
            derived = String.join(" ", java.util.stream.Stream.of(text(v, "first_name"), text(v, "last_name"))
                    .filter(s -> s != null && !s.isBlank()).map(String::trim).toList());
            if (derived.isBlank() && "SOLE_TRADER".equals(type)) {
                derived = text(v, "trading_name");
            }
        } else {
            derived = text(v, "trading_name") != null && !text(v, "trading_name").isBlank()
                    ? text(v, "trading_name") : text(v, "legal_name");
        }
        return derived == null || derived.isBlank() ? null : truncate(derived.trim(), 200);
    }

    /**
     * Next customer number of the tenant: "C" + 9-digit tenant sequence + Luhn check digit (e.g. C0000000018). The
     * per-tenant counter row is incremented atomically (upsert), so concurrent creates never collide.
     */
    String nextCustomerNumber() {
        long n = rows.jdbc().sql("""
                insert into customer_number_counter (tenant_id, last_value, created_by, updated_by)
                values (?::uuid, 1, ?, ?)
                on conflict (tenant_id) do update set last_value = customer_number_counter.last_value + 1
                returning last_value""").params(TenantContext.require().toString(), TenantContext.principal(), TenantContext.principal())
                .query(Long.class).single();
        String digits = String.format("%09d", n);
        return "C" + digits + luhn(digits);
    }

    /** Luhn (mod 10) check digit over a digit string. */
    static int luhn(String digits) {
        int sum = 0;
        boolean dbl = true;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            dbl = !dbl;
        }
        return (10 - sum % 10) % 10;
    }

    /** Shared field rules for create and update; derives is_minor from the date of birth. */
    private static void validateProfile(ObjectNode v, String type) {
        List<FieldError> errors = new ArrayList<>();
        LocalDate dob = date(v, "date_of_birth");
        if (dob != null) {
            if (dob.isAfter(today())) {
                errors.add(new FieldError("date_of_birth", "in_future", "Date of birth cannot be in the future"));
            } else if (Period.between(dob, today()).getYears() > 130) {
                errors.add(new FieldError("date_of_birth", "implausible", "Date of birth is implausible"));
            }
            v.put("is_minor", NATURAL_PERSON.contains(type) && Period.between(dob, today()).getYears() < AGE_OF_MAJORITY);
        }
        LocalDate inc = date(v, "incorporation_date");
        if (inc != null && inc.isAfter(today())) {
            errors.add(new FieldError("incorporation_date", "in_future", "Incorporation date cannot be in the future"));
        }
        for (String amount : List.of("annual_income", "annual_turnover")) {
            String a = text(v, amount);
            if (a != null && new java.math.BigDecimal(a).signum() < 0) {
                errors.add(new FieldError(amount, "negative", amount + " cannot be negative"));
            }
        }
        if (text(v, "annual_income") != null && text(v, "income_currency") == null) {
            errors.add(new FieldError("income_currency", "required", "income_currency is required with annual_income"));
        }
        if (text(v, "number_of_employees") != null && v.get("number_of_employees").asInt() < 0) {
            errors.add(new FieldError("number_of_employees", "negative", "number_of_employees cannot be negative"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(422, "invalid_customer", "Customer data is invalid", errors);
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Transactional
    public ObjectNode activate(UUID id) {
        ObjectNode c = rows.lock(T, id);
        String to = Lifecycles.CUSTOMER.apply("activate", text(c, "status"));
        List<FieldError> unmet = new ArrayList<>();
        if (KycTierPolicy.rank(text(c, "kyc_tier")) < 1) {
            unmet.add(new FieldError("kyc_tier", "tier_too_low", "KYC tier TIER_1 or higher is required"));
        }
        if ("PROHIBITED".equals(text(c, "risk_level"))) {
            unmet.add(new FieldError("risk_level", "prohibited_risk", "Customer risk is PROHIBITED"));
        }
        if (c.path("is_minor").asBoolean() && count("""
                select count(*) from customer_relationship where customer_id = ? and relationship_type = 'GUARDIAN'
                and (valid_to is null or valid_to >= current_date)""", id) == 0) {
            unmet.add(new FieldError("relationships", "guardian_required", "A minor needs an active guardian"));
        }
        if ("COMPANY".equals(text(c, "customer_type")) && count("""
                select count(*) from customer_relationship where customer_id = ?
                and relationship_type in ('DIRECTOR', 'AUTHORISED_SIGNATORY')
                and (valid_to is null or valid_to >= current_date)""", id) == 0) {
            unmet.add(new FieldError("relationships", "signatory_required", "A company needs a director or authorised signatory"));
        }
        if (!unmet.isEmpty()) {
            throw new ApiException(422, "kyc_requirements_not_met", "Customer cannot be activated yet", unmet);
        }
        ObjectNode v = status(c, "activate", to, null);
        if (c.get("onboarded_at") == null || c.get("onboarded_at").isNull()) {
            v.put("onboarded_at", now().toString());
        }
        v.putNull("dormant_since");
        return changeStatus(id, v);
    }

    @Transactional
    public ObjectNode suspend(UUID id, String reason) {
        ObjectNode c = rows.lock(T, id);
        String to = Lifecycles.CUSTOMER.apply("suspend", text(c, "status"));
        return changeStatus(id, status(c, "suspend", to, reason));
    }

    @Transactional
    public ObjectNode reactivate(UUID id, String reason) {
        ObjectNode c = rows.lock(T, id);
        String to = Lifecycles.CUSTOMER.apply("reactivate", text(c, "status"));
        if ("PROHIBITED".equals(text(c, "risk_level"))) {
            throw ApiException.unprocessable("prohibited_risk", "A PROHIBITED-risk customer cannot be reactivated");
        }
        ObjectNode v = status(c, "reactivate", to, reason);
        v.putNull("dormant_since");
        return changeStatus(id, v);
    }

    @Transactional
    public ObjectNode close(UUID id, String reason) {
        ObjectNode c = rows.lock(T, id);
        String to = Lifecycles.CUSTOMER.apply("close", text(c, "status"));
        ObjectNode v = status(c, "close", to, reason);
        v.put("closed_at", now().toString());
        v.put("closure_reason", truncate(reason, 255));
        revokeAccess(id, "Customer closed");
        return changeStatus(id, v);
    }

    @Transactional
    public ObjectNode markDeceased(UUID id, LocalDate deceasedOn, UUID documentId) {
        ObjectNode c = rows.lock(T, id);
        if (!NATURAL_PERSON.contains(text(c, "customer_type"))) {
            throw ApiException.unprocessable("not_a_natural_person", "Only individuals can be marked deceased");
        }
        String to = Lifecycles.CUSTOMER.apply("mark-deceased", text(c, "status"));
        LocalDate dob = date(c, "date_of_birth");
        if (deceasedOn.isAfter(today()) || (dob != null && deceasedOn.isBefore(dob))) {
            throw ApiException.unprocessable("invalid_deceased_on", "deceased_on must be between birth and today");
        }
        ObjectNode v = status(c, "mark-deceased", to, "Death notified, deceased on " + deceasedOn);
        v.put("deceased_document_id", documentId.toString());
        v.put("deceased_on", deceasedOn.toString());
        revokeAccess(id, "Customer deceased");
        return changeStatus(id, v);
    }

    /** New status plus the audit columns of the change (who, when, why; the action when no reason is given). */
    private static ObjectNode status(ObjectNode current, String action, String to, String reason) {
        ObjectNode v = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        v.put("status", to);
        v.put("status_reason", truncate(reason == null ? action : reason, 255));
        v.put("status_changed_at", now().toString());
        v.put("status_changed_by", truncate(TenantContext.principal(), 64));
        return v;
    }

    private ObjectNode changeStatus(UUID id, ObjectNode v) {
        ObjectNode row = rows.update(T, id, v);
        events.publish("party.customer.status_changed", T, row, Customer.class);
        return row;
    }

    /** Channels are frozen with the relationship: devices and credentials stop working. */
    private void revokeAccess(UUID customerId, String reason) {
        rows.execute("""
                update customer_device set status = 'REVOKED', revoked_at = now(), revocation_reason = ?, push_token = null
                where customer_id = ? and status <> 'REVOKED'""", reason, customerId);
        rows.execute("update customer_credential set status = 'REVOKED' where customer_id = ? and status <> 'REVOKED'",
                customerId);
    }

    // ------------------------------------------------------------------ KYC tier (maker-checker)

    public record TierResult(ObjectNode customer, boolean pending) {}

    /**
     * Downgrades apply at once (risk-reducing). Upgrades need the evidence for the tier and are maker-checker: the
     * request opens a workflow-service approval request and parks the target in {@code pending_tier}; the change
     * takes effect when {@code platform.approval_request.approved} arrives (four-eyes is enforced by workflow-service).
     */
    @Transactional
    public TierResult changeTier(UUID id, String toTier, String reason, String idempotencyKey) {
        ObjectNode c = rows.lock(T, id);
        if (!Lifecycles.CUSTOMER_OPEN.contains(text(c, "status"))) {
            throw ApiException.conflict("customer_closed", "The KYC tier of a " + text(c, "status") + " customer cannot change");
        }
        if (!KycTierPolicy.TIERS.contains(toTier)) {
            throw ApiException.unprocessable("invalid_tier", "Unknown tier " + toTier);
        }
        String from = text(c, "kyc_tier");
        if (from.equals(toTier)) {
            throw ApiException.conflict("tier_unchanged", "Customer is already " + toTier);
        }
        String pending = text(c, "pending_tier");
        if (KycTierPolicy.rank(toTier) < KycTierPolicy.rank(from)) {
            ObjectNode v = rows.object();
            v.putNull("pending_tier");
            v.putNull("tier_approval_request_id");
            ObjectNode row = applyTier(id, c, v, toTier, reason);
            if (pending != null) {
                // the parked upgrade is superseded: close its inbox item (after commit)
                approvals.cancelOpen(TIER_APPROVAL_RESOURCE, id, TIER_APPROVAL_ACTION, "superseded by downgrade to " + toTier);
            }
            return new TierResult(row, false);
        }
        if (pending != null) {
            throw ApiException.conflict("tier_change_pending", "A change to " + pending
                    + " is awaiting approval (request " + text(c, "tier_approval_request_id") + ")");
        }
        List<FieldError> unmet = KycTierPolicy.unmet(toTier, evidence(id, c));
        if (!unmet.isEmpty()) {
            throw new ApiException(422, "tier_requirements_not_met", "Evidence does not support " + toTier, unmet);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("customer_id", id.toString());
        payload.put("from_tier", from);
        payload.put("to_tier", toTier);
        payload.put("reason", truncate(reason, 255));
        payload.put("requested_by", TenantContext.principal());
        if (!approvals.centralised()) {
            throw new ApiException(503, "approvals_unavailable",
                    "KYC tier upgrades are approved in workflow-service; set meridian.approvals.mode=workflow");
        }
        JsonNode approval = approvals.request(ApprovalSpec.of(TIER_APPROVAL_RESOURCE, id, TIER_APPROVAL_ACTION,
                "KYC tier " + from + " -> " + toTier + " for " + text(c, "customer_number"), payload)
                .withSummary(truncate(reason, 255)), "tier:" + id + ":" + idempotencyKey);
        UUID requestId = Approvals.idOf(approval);
        String approvalId = requestId == null ? null : requestId.toString();
        if (approvalId == null) {
            throw new ApiException(502, "upstream_error", "workflow-service did not return an approval request id");
        }
        ObjectNode v = rows.object();
        v.put("pending_tier", toTier);
        v.put("tier_approval_request_id", approvalId);
        ObjectNode row = rows.update(T, id, v);
        events.publish("party.customer.tier_change_requested", T, row, Customer.class);
        return new TierResult(row, true);
    }

    /**
     * Applies ({@code approved}) or drops ({@code rejected}, {@code changes_requested}, {@code expired},
     * {@code cancelled}) the pending tier upgrade linked to the approval request. Returns false when the request is
     * not (or no longer) the pending one, which makes redelivered events harmless. The evidence is re-checked at
     * execution time: an approved upgrade the evidence no longer supports, for a customer closed meanwhile, or
     * approved by the principal who requested it (four-eyes) is dropped instead of applied.
     *
     * @param approval the approval request row (id, resource_id, payload)
     * @param outcome  lower-case outcome ({@code approved}, {@code rejected}, ...)
     * @param decidedBy the deciding approver
     */
    @Transactional
    public boolean onTierApprovalDecided(JsonNode approval, String outcome, String decidedBy) {
        UUID customerId = Json.uuid(approval, "resource_id");
        String approvalId = text(approval, "id");
        if (customerId == null || approvalId == null || rows.find(T, customerId).isEmpty()) {
            return false;
        }
        ObjectNode c = rows.lock(T, customerId);
        String toTier = text(c, "pending_tier");
        if (toTier == null || !approvalId.equals(text(c, "tier_approval_request_id"))) {
            return false;
        }
        ObjectNode clear = rows.object();
        clear.putNull("pending_tier");
        clear.putNull("tier_approval_request_id");
        String requestedTo = text(approval.path("payload"), "to_tier");
        String requestedBy = text(approval.path("payload"), "requested_by");
        boolean fourEyes = requestedBy == null || decidedBy == null || !requestedBy.equals(decidedBy);
        boolean applicable = "approved".equals(outcome) && fourEyes && (requestedTo == null || requestedTo.equals(toTier))
                && Lifecycles.CUSTOMER_OPEN.contains(text(c, "status"))
                && KycTierPolicy.unmet(toTier, evidence(customerId, c)).isEmpty();
        if (!applicable) {
            rows.update(T, customerId, clear);
            return true;
        }
        String reason = text(approval.path("payload"), "reason");
        applyTier(customerId, c, clear, toTier,
                (reason == null ? "Tier upgrade" : reason) + " (approved, request " + approvalId + ")");
        return true;
    }

    private ObjectNode applyTier(UUID id, ObjectNode c, ObjectNode values, String toTier, String reason) {
        ObjectNode h = rows.object();
        h.put("customer_id", id.toString());
        h.put("from_tier", text(c, "kyc_tier"));
        h.put("to_tier", toTier);
        h.put("reason", truncate(reason, 255));
        h.put("effective_at", now().toString());
        ObjectNode history = rows.insert("customer_kyc_tier_history", h);
        ObjectNode v = values.deepCopy();
        v.put("kyc_tier", toTier);
        ObjectNode row = rows.update(T, id, v);
        ObjectNode payload = (ObjectNode) json.valueToTree(rows.read(history, CustomerKycTierHistory.class));
        payload.set("limits", json.valueToTree(KycTierPolicy.limits(toTier).asMap()));
        events.publishPayload("party.customer.tier_changed", "customer_kyc_tier_history",
                UUID.fromString(history.get("id").asString()), payload);
        return row;
    }

    KycTierPolicy.Evidence evidence(UUID id, ObjectNode c) {
        boolean individual = NATURAL_PERSON.contains(text(c, "customer_type"));
        int contacts = count("select count(*) from customer_contact where customer_id = ? and is_verified", id);
        int identifiers = count("""
                select count(*) from customer_identifier where customer_id = ?
                and (expiry_date is null or expiry_date >= current_date)""", id);
        int addresses = count("""
                select count(*) from customer_address where customer_id = ? and address_type in ('RESIDENTIAL', 'REGISTERED')
                and (valid_to is null or valid_to >= current_date)""", id);
        boolean birthOrReg = individual ? text(c, "date_of_birth") != null : text(c, "registration_number") != null;
        return new KycTierPolicy.Evidence(individual, contacts, identifiers, birthOrReg, addresses, text(c, "risk_level"));
    }

    // ------------------------------------------------------------------ merge

    /**
     * Folds a duplicate into this (surviving) customer: contact data, addresses, identifiers, relationships, group
     * memberships, segments, consents and privacy requests move to the survivor; the duplicate's devices and
     * credentials are revoked rather than transferred (an authenticator must never silently gain access to another
     * record); the duplicate is closed and points at the survivor.
     */
    @Transactional
    public ObjectNode merge(UUID survivorId, UUID duplicateId, String reason) {
        if (survivorId.equals(duplicateId)) {
            throw ApiException.unprocessable("self_merge", "A customer cannot be merged into itself");
        }
        ObjectNode survivor;
        ObjectNode duplicate;
        if (survivorId.compareTo(duplicateId) < 0) {
            survivor = rows.lock(T, survivorId);
            duplicate = lockDuplicate(duplicateId);
        } else {
            duplicate = lockDuplicate(duplicateId);
            survivor = rows.lock(T, survivorId);
        }
        if (!Lifecycles.CUSTOMER_OPEN.contains(text(survivor, "status"))) {
            throw ApiException.conflict("invalid_transition", "The surviving customer is " + text(survivor, "status"));
        }
        String dupTo = Lifecycles.CUSTOMER.apply("merge", text(duplicate, "status"));
        if (NATURAL_PERSON.contains(text(survivor, "customer_type")) != NATURAL_PERSON.contains(text(duplicate, "customer_type"))) {
            throw ApiException.unprocessable("incompatible_customer_types",
                    "An individual and an organisation cannot be merged");
        }
        // Contacts: drop the duplicate's copies of contact points the survivor already has. Verification challenges
        // follow their contact; open ones are superseded (a code sent for the duplicate must not verify the survivor).
        rows.execute("""
                update contact_verification set customer_id = ?, status = case when status in ('PENDING', 'LOCKED')
                then 'SUPERSEDED' else status end where customer_id = ?""", survivorId, duplicateId);
        rows.execute("""
                delete from contact_verification v where v.customer_contact_id in (select d.id from customer_contact d
                where d.customer_id = ? and exists (select 1 from customer_contact s
                where s.customer_id = ? and s.contact_type = d.contact_type
                and coalesce(s.phone, '') = coalesce(d.phone, '') and coalesce(lower(s.email), '') = coalesce(lower(d.email), '')))""",
                duplicateId, survivorId);
        rows.execute("""
                delete from customer_contact d where d.customer_id = ? and exists (select 1 from customer_contact s
                where s.customer_id = ? and s.contact_type = d.contact_type
                and coalesce(s.phone, '') = coalesce(d.phone, '') and coalesce(lower(s.email), '') = coalesce(lower(d.email), ''))""",
                duplicateId, survivorId);
        rows.execute("update customer_contact set customer_id = ?, is_primary = false where customer_id = ?", survivorId, duplicateId);
        rows.execute("update customer_address set customer_id = ?, is_primary = false where customer_id = ?", survivorId, duplicateId);
        rows.execute("update customer_identifier set customer_id = ?, is_primary = false where customer_id = ?", survivorId, duplicateId);
        // Relationships: remove links between the two records and ones the survivor already has.
        rows.execute("""
                delete from customer_relationship where (customer_id = ? and related_customer_id = ?)
                or (customer_id = ? and related_customer_id = ?)""", duplicateId, survivorId, survivorId, duplicateId);
        rows.execute("""
                delete from customer_relationship d where d.customer_id = ? and exists (select 1 from customer_relationship s
                where s.customer_id = ? and s.related_customer_id = d.related_customer_id and s.relationship_type = d.relationship_type)""",
                duplicateId, survivorId);
        rows.execute("update customer_relationship set customer_id = ? where customer_id = ?", survivorId, duplicateId);
        rows.execute("""
                delete from customer_relationship d where d.related_customer_id = ? and exists (select 1 from customer_relationship s
                where s.related_customer_id = ? and s.customer_id = d.customer_id and s.relationship_type = d.relationship_type)""",
                duplicateId, survivorId);
        rows.execute("update customer_relationship set related_customer_id = ? where related_customer_id = ?", survivorId, duplicateId);
        rows.execute("""
                delete from customer_group_member d where d.customer_id = ? and exists (select 1 from customer_group_member s
                where s.customer_id = ? and s.customer_group_id = d.customer_group_id)""", duplicateId, survivorId);
        rows.execute("update customer_group_member set customer_id = ? where customer_id = ?", survivorId, duplicateId);
        rows.execute("update customer_group set lead_customer_id = ? where lead_customer_id = ?", survivorId, duplicateId);
        rows.execute("""
                delete from customer_segment_assignment d where d.customer_id = ? and exists (select 1 from customer_segment_assignment s
                where s.customer_id = ? and s.customer_segment_id = d.customer_segment_id and s.valid_to is null)""",
                duplicateId, survivorId);
        rows.execute("update customer_segment_assignment set customer_id = ? where customer_id = ?", survivorId, duplicateId);
        rows.execute("update consent set customer_id = ? where customer_id = ?", survivorId, duplicateId);
        rows.execute("update privacy_request set customer_id = ? where customer_id = ?", survivorId, duplicateId);
        revokeAccess(duplicateId, "Merged into " + text(survivor, "customer_number"));

        ObjectNode dupValues = status(duplicate, "merge", dupTo, reason);
        dupValues.put("merged_into_customer_id", survivorId.toString());
        dupValues.put("closed_at", now().toString());
        dupValues.put("closure_reason", truncate("MERGED into " + text(survivor, "customer_number") + ": " + reason, 255));
        changeStatus(duplicateId, dupValues);

        ObjectNode sv = rows.object();
        if (!survivor.path("is_pep").asBoolean() && duplicate.path("is_pep").asBoolean()) {
            sv.put("is_pep", true);
        }
        ObjectNode row = rows.update(T, survivorId, sv);
        events.publish("party.customer.merged", T, row, Customer.class);
        return row;
    }

    private ObjectNode lockDuplicate(UUID id) {
        try {
            return rows.lock(T, id);
        } catch (ApiException e) {
            throw ApiException.unprocessable("duplicate_not_found", "Duplicate customer " + id + " was not found");
        }
    }

    // ------------------------------------------------------------------ helpers

    int count(String sql, Object... args) {
        return rows.jdbc().sql(sql).params(java.util.Arrays.asList(args)).query(Integer.class).single();
    }

    static int parseVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw ApiException.badRequest("if_match_required", "If-Match header with the current row_version is required");
        }
        try {
            return Integer.parseInt(ifMatch.replace("W/", "").replace("\"", "").trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("invalid_if_match", "If-Match must be the resource's row_version");
        }
    }

    static ApiException versionConflict(int expected) {
        return ApiException.conflict("version_conflict",
                "If-Match " + expected + " does not match the current version; reload and retry");
    }
}
