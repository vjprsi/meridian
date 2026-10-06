package io.meridian.customer.application;

import static io.meridian.customer.application.Json.now;
import static io.meridian.customer.application.Json.text;

import io.meridian.common.error.ApiException;
import io.meridian.customer.api.generated.model.Consent;
import io.meridian.customer.domain.Lifecycles;
import io.meridian.customer.infrastructure.Rows;
import io.meridian.web.crud.CrudEngine;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Consent records (GDPR / LGPD / POPIA / open banking). One live consent per customer, purpose and recipient;
 * consents past their expiry are treated as EXPIRED; terms and privacy policy cannot be withdrawn while the
 * customer relationship is active (the relationship must be closed instead).
 */
@Service
public class ConsentService {
    static final String T = "consent";
    private static final Set<String> MANDATORY = Set.of("TERMS_AND_CONDITIONS", "PRIVACY_POLICY");
    private static final Set<String> LAWFUL_BASES = Set.of("CONSENT", "CONTRACT", "LEGAL_OBLIGATION", "LEGITIMATE_INTEREST");

    private final Rows rows;
    private final CrudEngine crud;
    private final Guards guards;
    private final Events events;
    private final JsonMapper json;

    public ConsentService(Rows rows, CrudEngine crud, Guards guards, Events events, JsonMapper json) {
        this.rows = rows;
        this.crud = crud;
        this.guards = guards;
        this.events = events;
        this.json = json;
    }

    @Transactional
    public ObjectNode create(Object body) {
        ObjectNode v = crud.toColumns(T, body);
        UUID customerId = UUID.fromString(text(v, "customer_id"));
        guards.openCustomer(customerId);
        String status = text(v, "status");
        if (status != null && !"GIVEN".equals(status)) {
            throw ApiException.unprocessable("invalid_initial_status", "A consent is recorded as GIVEN; withdraw it afterwards");
        }
        v.put("status", "GIVEN");
        OffsetDateTime given = Json.timestamp(v, "given_at");
        OffsetDateTime expires = Json.timestamp(v, "expires_at");
        if (given.isAfter(now().plusMinutes(5))) {
            throw ApiException.unprocessable("invalid_given_at", "given_at cannot be in the future");
        }
        if (expires != null && !expires.isAfter(given)) {
            throw ApiException.unprocessable("invalid_expires_at", "expires_at must be after given_at");
        }
        String basis = text(v, "lawful_basis");
        if (basis != null && !LAWFUL_BASES.contains(basis)) {
            throw ApiException.unprocessable("invalid_lawful_basis", "lawful_basis must be one of " + LAWFUL_BASES);
        }
        expireOverdue(customerId);
        int live = rows.jdbc().sql("""
                select count(*) from consent where customer_id = ? and purpose = ? and coalesce(recipient, '') = coalesce(?, '')
                and status = 'GIVEN'""").params(customerId, text(v, "purpose"), text(v, "recipient")).query(Integer.class).single();
        if (live > 0) {
            throw ApiException.conflict("consent_exists", "A live consent for this purpose and recipient exists; renew it instead");
        }
        ObjectNode row = rows.insert(T, v);
        events.publish("party.consent.given", T, row, Consent.class);
        return row;
    }

    @Transactional
    public ObjectNode withdraw(UUID id, String reason) {
        ObjectNode c = lockCurrent(id);
        String to = Lifecycles.CONSENT.apply("withdraw", text(c, "status"));
        if (MANDATORY.contains(text(c, "purpose"))) {
            String customerStatus = text(guards.existingCustomer(UUID.fromString(text(c, "customer_id"))), "status");
            if (!"CLOSED".equals(customerStatus) && !"DECEASED".equals(customerStatus) && !"PROSPECT".equals(customerStatus)) {
                throw ApiException.unprocessable("consent_required",
                        text(c, "purpose") + " cannot be withdrawn while the relationship is open; close the customer instead");
            }
        }
        ObjectNode v = rows.object();
        v.put("status", to);
        v.put("withdrawn_at", now().toString());
        if (reason != null) {
            v.set("evidence", withNote(c, "withdrawal_reason", reason));
        }
        ObjectNode row = rows.update(T, id, v);
        events.publish("party.consent.withdrawn", T, row, Consent.class);
        return row;
    }

    @Transactional
    public ObjectNode renew(UUID id, OffsetDateTime expiresAt, JsonNode evidence) {
        ObjectNode c = lockCurrent(id);
        String to = Lifecycles.CONSENT.apply("renew", text(c, "status"));
        guards.openCustomer(UUID.fromString(text(c, "customer_id")));
        OffsetDateTime now = now();
        if (!expiresAt.isAfter(now)) {
            throw ApiException.unprocessable("invalid_expires_at", "expires_at must be in the future");
        }
        OffsetDateTime current = Json.timestamp(c, "expires_at");
        if (current != null && !expiresAt.isAfter(current)) {
            throw ApiException.unprocessable("invalid_expires_at", "A renewal must extend the current expiry");
        }
        ObjectNode v = rows.object();
        v.put("status", to);
        v.put("given_at", now.toString());
        v.put("expires_at", expiresAt.toString());
        if (evidence != null && !evidence.isNull()) {
            v.set("evidence", evidence);
        }
        ObjectNode row = rows.update(T, id, v);
        events.publish("party.consent.given", T, row, Consent.class);
        return row;
    }

    /** Withdraws live consents for the purposes (privacy objection / restriction); returns how many. */
    int withdrawPurposes(UUID customerId, Set<String> purposes, String reason) {
        int n = 0;
        for (ObjectNode c : rows.query("""
                select row_to_json(t)::text from consent t where t.customer_id = ? and t.status = 'GIVEN'
                and t.purpose = any(?) for update""", customerId, purposes.toArray(String[]::new))) {
            ObjectNode v = rows.object();
            v.put("status", "WITHDRAWN");
            v.put("withdrawn_at", now().toString());
            v.set("evidence", withNote(c, "withdrawal_reason", reason));
            ObjectNode row = rows.update(T, UUID.fromString(text(c, "id")), v);
            events.publish("party.consent.withdrawn", T, row, Consent.class);
            n++;
        }
        return n;
    }

    /** Locks the consent, first moving it to EXPIRED when its expiry has passed. */
    private ObjectNode lockCurrent(UUID id) {
        ObjectNode c = rows.lock(T, id);
        OffsetDateTime exp = Json.timestamp(c, "expires_at");
        if ("GIVEN".equals(text(c, "status")) && exp != null && exp.isBefore(now())) {
            ObjectNode v = rows.object();
            v.put("status", "EXPIRED");
            c = rows.update(T, id, v);
            events.publish("party.consent.withdrawn", T, c, Consent.class);
        }
        return c;
    }

    private void expireOverdue(UUID customerId) {
        for (ObjectNode c : rows.query("""
                select row_to_json(t)::text from consent t where t.customer_id = ? and t.status = 'GIVEN'
                and t.expires_at < now() for update""", customerId)) {
            ObjectNode v = rows.object();
            v.put("status", "EXPIRED");
            ObjectNode row = rows.update(T, UUID.fromString(text(c, "id")), v);
            events.publish("party.consent.withdrawn", T, row, Consent.class);
        }
    }

    private ObjectNode withNote(ObjectNode consent, String key, String note) {
        JsonNode ev = consent.get("evidence");
        ObjectNode e = ev != null && ev.isObject() ? ((ObjectNode) ev).deepCopy() : json.createObjectNode();
        e.put(key, Json.truncate(note, 255));
        return e;
    }
}
