package io.meridian.customer.application;

import static io.meridian.customer.application.Json.text;
import static io.meridian.customer.application.Json.today;

import io.meridian.common.error.ApiException;
import io.meridian.customer.domain.Normalizer;
import io.meridian.customer.infrastructure.Crypto;
import io.meridian.customer.infrastructure.Rows;
import io.meridian.web.crud.CrudEngine;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

/**
 * Government and registry identifiers. The value is kept for display/KYC; a keyed hash of its normalised form
 * (value_hash) drives exact-match search and the per-tenant uniqueness that blocks duplicate parties.
 */
@Service
public class IdentifierService {
    static final String T = "customer_identifier";

    private final Rows rows;
    private final CrudEngine crud;
    private final Guards guards;
    private final Crypto crypto;

    public IdentifierService(Rows rows, CrudEngine crud, Guards guards, Crypto crypto) {
        this.rows = rows;
        this.crud = crud;
        this.guards = guards;
        this.crypto = crypto;
    }

    @Transactional
    public ObjectNode create(UUID customerId, Object body) {
        guards.openCustomer(customerId);
        ObjectNode v = crud.toColumns(T, body);
        v.put("customer_id", customerId.toString());
        prepare(v, v);
        LocalDate expiry = Json.date(v, "expiry_date");
        if (expiry != null && expiry.isBefore(today())) {
            throw ApiException.unprocessable("identifier_expired", "The identifier has already expired");
        }
        if (v.path("is_primary").asBoolean()) {
            clearPrimary(customerId, text(v, "identifier_type"), null);
        }
        return rows.insert(T, v);
    }

    @Transactional
    public ObjectNode update(UUID customerId, UUID id, String ifMatch, Object body) {
        int expected = CustomerService.parseVersion(ifMatch);
        ObjectNode current = guards.child(T, customerId, id, true);
        ObjectNode v = crud.toColumns(T, body);
        v.remove("customer_id");
        ObjectNode merged = current.deepCopy();
        merged.setAll(v);
        prepare(merged, v);
        if (v.has("identifier_value") && !text(current, "value_hash").equals(text(v, "value_hash"))) {
            v.put("verified", false);
            v.putNull("verified_at");
        }
        if (v.path("is_primary").asBoolean()) {
            clearPrimary(customerId, text(merged, "identifier_type"), id);
        }
        return rows.update(T, id, v, expected).orElseThrow(() -> CustomerService.versionConflict(expected));
    }

    /** Validates dates and (re)computes the value hash into {@code target}. */
    private void prepare(ObjectNode merged, ObjectNode target) {
        String value = text(merged, "identifier_value");
        String normalised = Normalizer.identifier(value);
        if (normalised.length() < 3) {
            throw ApiException.unprocessable("invalid_identifier", "identifier_value is too short");
        }
        LocalDate issue = Json.date(merged, "issue_date");
        LocalDate expiry = Json.date(merged, "expiry_date");
        if (issue != null && issue.isAfter(today())) {
            throw ApiException.unprocessable("invalid_issue_date", "issue_date cannot be in the future");
        }
        if (issue != null && expiry != null && !expiry.isAfter(issue)) {
            throw ApiException.unprocessable("invalid_expiry_date", "expiry_date must be after issue_date");
        }
        if (target.has("identifier_value")) {
            target.put("identifier_value", value.trim());
        }
        target.put("value_hash", crypto.identifierHash(normalised));
    }

    private void clearPrimary(UUID customerId, String type, UUID except) {
        rows.execute("update customer_identifier set is_primary = false where customer_id = ? and identifier_type = ? and is_primary and id <> ?",
                customerId, type, except == null ? new UUID(0, 0) : except);
    }
}
