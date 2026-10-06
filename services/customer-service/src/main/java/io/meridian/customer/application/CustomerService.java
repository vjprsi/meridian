package io.meridian.customer.application;

import static io.meridian.customer.application.Json.date;
import static io.meridian.customer.application.Json.now;
import static io.meridian.customer.application.Json.text;
import static io.meridian.customer.application.Json.today;
import static io.meridian.customer.application.Json.truncate;

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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Customer master data: creation, maintenance, lifecycle, KYC tier (maker-checker) and duplicate merge. */
@Service
public class CustomerService {
    static final String T = "customer";
    static final Set<String> NATURAL_PERSON = Set.of("INDIVIDUAL", "SOLE_TRADER");
    private static final Pattern CUSTOMER_NUMBER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/-]{2,31}$");
    private static final int AGE_OF_MAJORITY = 18;
    static final String PENDING_TIER = "_pending_tier_change";
    static final String LIFECYCLE = "_lifecycle";

    private final Rows rows;
    private final CrudEngine crud;
    private final Events events;
    private final JsonMapper json;

    public CustomerService(Rows rows, CrudEngine crud, Events events, JsonMapper json) {
        this.rows = rows;
        this.crud = crud;
        this.events = events;
        this.json = json;
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
        String number = text(v, "customer_number").trim();
        if (!CUSTOMER_NUMBER.matcher(number).matches()) {
            throw ApiException.unprocessable("invalid_customer_number",
                    "customer_number must be 3-32 letters, digits or . _ / - separators");
        }
        v.put("customer_number", number);
        validateProfile(v, text(v, "customer_type"));
        if (!NATURAL_PERSON.contains(text(v, "customer_type")) && text(v, "legal_name") == null) {
            v.put("legal_name", text(v, "display_name"));
        }
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
        ObjectNode v = status(c, "mark-deceased", to, null);
        ((ObjectNode) v.get("metadata").get(LIFECYCLE)).put("document_id", documentId.toString());
        v.put("deceased_on", deceasedOn.toString());
        revokeAccess(id, "Customer deceased");
        return changeStatus(id, v);
    }

    /** New status plus an audit note (who, when, why) kept under metadata._lifecycle. */
    private ObjectNode status(ObjectNode current, String action, String to, String reason) {
        ObjectNode v = rows.object();
        v.put("status", to);
        ObjectNode meta = metadata(current);
        ObjectNode note = meta.putObject(LIFECYCLE);
        note.put("action", action);
        note.put("from", text(current, "status"));
        note.put("to", to);
        note.put("by", TenantContext.principal());
        note.put("at", now().toString());
        if (reason != null) {
            note.put("reason", truncate(reason, 255));
        }
        v.set("metadata", meta);
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
     * Downgrades apply at once (risk-reducing). Upgrades need the evidence for the tier, are requested by a maker
     * and take effect when a different user (the checker) confirms the same target tier.
     */
    @Transactional
    public TierResult changeTier(UUID id, String toTier, String reason) {
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
        ObjectNode meta = metadata(c);
        JsonNode pending = meta.get(PENDING_TIER);
        if (KycTierPolicy.rank(toTier) < KycTierPolicy.rank(from)) {
            meta.remove(PENDING_TIER);
            return new TierResult(applyTier(id, c, meta, toTier, reason), false);
        }
        List<FieldError> unmet = KycTierPolicy.unmet(toTier, evidence(id, c));
        if (!unmet.isEmpty()) {
            throw new ApiException(422, "tier_requirements_not_met", "Evidence does not support " + toTier, unmet);
        }
        String principal = TenantContext.principal();
        if (pending == null || pending.isNull()) {
            ObjectNode request = meta.putObject(PENDING_TIER);
            request.put("to_tier", toTier);
            request.put("reason", truncate(reason, 255));
            request.put("requested_by", principal);
            request.put("requested_at", now().toString());
            ObjectNode v = rows.object();
            v.set("metadata", meta);
            return new TierResult(rows.update(T, id, v), true);
        }
        if (!toTier.equals(text(pending, "to_tier"))) {
            throw ApiException.conflict("tier_change_pending",
                    "A change to " + text(pending, "to_tier") + " is awaiting approval");
        }
        if (principal.equals(text(pending, "requested_by"))) {
            throw ApiException.unprocessable("four_eyes_violation",
                    "The tier change must be approved by a different user than the requester");
        }
        String combined = truncate(text(pending, "reason") + " (approved by " + principal + ": " + reason + ")", 255);
        meta.remove(PENDING_TIER);
        return new TierResult(applyTier(id, c, meta, toTier, combined), false);
    }

    private ObjectNode applyTier(UUID id, ObjectNode c, ObjectNode meta, String toTier, String reason) {
        ObjectNode h = rows.object();
        h.put("customer_id", id.toString());
        h.put("from_tier", text(c, "kyc_tier"));
        h.put("to_tier", toTier);
        h.put("reason", truncate(reason, 255));
        h.put("effective_at", now().toString());
        ObjectNode history = rows.insert("customer_kyc_tier_history", h);
        ObjectNode v = rows.object();
        v.put("kyc_tier", toTier);
        v.set("metadata", meta);
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
        // Contacts: drop the duplicate's copies of contact points the survivor already has.
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
        ((ObjectNode) dupValues.get("metadata")).put("merged_into", survivorId.toString());
        dupValues.put("closed_at", now().toString());
        dupValues.put("closure_reason", truncate("MERGED into " + text(survivor, "customer_number") + ": " + reason, 255));
        changeStatus(duplicateId, dupValues);

        ObjectNode survivorMeta = metadata(survivor);
        ArrayNode mergedFrom = survivorMeta.has("merged_from") && survivorMeta.get("merged_from").isArray()
                ? (ArrayNode) survivorMeta.get("merged_from") : survivorMeta.putArray("merged_from");
        mergedFrom.add(duplicateId.toString());
        ObjectNode sv = rows.object();
        sv.set("metadata", survivorMeta);
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

    ObjectNode metadata(ObjectNode customer) {
        JsonNode m = customer.get("metadata");
        return m != null && m.isObject() ? ((ObjectNode) m).deepCopy() : rows.object();
    }

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
