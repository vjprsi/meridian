package io.meridian.customer.application;

import static io.meridian.customer.application.Json.text;

import io.meridian.common.error.ApiException;
import io.meridian.customer.api.generated.model.CustomerRelationship;
import io.meridian.customer.infrastructure.Rows;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

/**
 * Relationships between customers. Rules: no self-relationships; ownership shares are 0-100 and, per subject
 * and type, active shares never exceed 100%; ownership and UBO links need an organisation as subject.
 * Concurrent changes for one subject are serialised by locking the subject customer.
 */
@Service
public class RelationshipService {
    static final String T = "customer_relationship";
    static final Set<String> OWNERSHIP = Set.of("SHAREHOLDER", "ULTIMATE_BENEFICIAL_OWNER", "PARENT_COMPANY");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final Rows rows;
    private final CrudEngine crud;
    private final Events events;

    public RelationshipService(Rows rows, CrudEngine crud, Events events) {
        this.rows = rows;
        this.crud = crud;
        this.events = events;
    }

    @Transactional
    public ObjectNode create(Object body) {
        ObjectNode v = crud.toColumns(T, body);
        validate(v, null);
        ObjectNode row = rows.insert(T, v);
        events.publish("party.customer_relationship.changed", T, row, CustomerRelationship.class);
        return row;
    }

    @Transactional
    public ObjectNode update(UUID id, String ifMatch, Object body) {
        int expected = CustomerService.parseVersion(ifMatch);
        ObjectNode current = rows.get(T, id);
        ObjectNode v = crud.toColumns(T, body);
        for (String key : new String[] {"customer_id", "related_customer_id", "relationship_type"}) {
            if (v.has(key) && !text(v, key).equals(text(current, key))) {
                throw ApiException.unprocessable("immutable_field", key + " cannot be changed; end this relationship and create a new one");
            }
            v.remove(key);
        }
        ObjectNode merged = current.deepCopy();
        merged.setAll(v);
        validate(merged, id);
        ObjectNode row = rows.update(T, id, v, expected).orElseThrow(() -> CustomerService.versionConflict(expected));
        events.publish("party.customer_relationship.changed", T, row, CustomerRelationship.class);
        return row;
    }

    @Transactional
    public void delete(UUID id) {
        ObjectNode current = rows.lock(T, id);
        rows.execute("delete from customer_relationship where id = ?", id);
        events.publish("party.customer_relationship.changed", T, current, CustomerRelationship.class);
    }

    private void validate(ObjectNode v, UUID self) {
        UUID subject = UUID.fromString(text(v, "customer_id"));
        UUID related = UUID.fromString(text(v, "related_customer_id"));
        String type = text(v, "relationship_type");
        if (subject.equals(related)) {
            throw ApiException.unprocessable("self_relationship", "A customer cannot be related to itself");
        }
        ObjectNode subj = rows.lock("customer", subject);
        ObjectNode rel = rows.find("customer", related)
                .orElseThrow(() -> ApiException.unprocessable("customer_not_found", "Related customer " + related + " was not found"));
        for (ObjectNode c : new ObjectNode[] {subj, rel}) {
            if ("CLOSED".equals(text(c, "status")) && self == null) {
                throw ApiException.conflict("customer_closed", "Relationships cannot be added to a closed customer");
            }
        }
        if (text(v, "valid_to") != null && text(v, "valid_to").compareTo(text(v, "valid_from")) < 0) {
            throw ApiException.unprocessable("invalid_period", "valid_to must not be before valid_from");
        }
        BigDecimal pct = text(v, "ownership_percentage") == null ? null : new BigDecimal(text(v, "ownership_percentage"));
        BigDecimal indirect = text(v, "indirect_ownership_percentage") == null ? null
                : new BigDecimal(text(v, "indirect_ownership_percentage"));
        for (BigDecimal p : new BigDecimal[] {pct, indirect}) {
            if (p != null && (p.signum() < 0 || p.compareTo(HUNDRED) > 0)) {
                throw ApiException.unprocessable("invalid_percentage", "Ownership percentages must be between 0 and 100");
            }
        }
        if (OWNERSHIP.contains(type)) {
            if (CustomerService.NATURAL_PERSON.contains(text(subj, "customer_type"))) {
                throw ApiException.unprocessable("invalid_relationship", type + " requires an organisation as the subject customer");
            }
            if (pct != null) {
                BigDecimal others = rows.jdbc().sql("""
                        select coalesce(sum(ownership_percentage), 0) from customer_relationship
                        where customer_id = ? and relationship_type = ? and id <> ?
                        and (valid_to is null or valid_to >= current_date)""")
                        .params(subject, type, self == null ? new UUID(0, 0) : self).query(BigDecimal.class).single();
                if (others.add(pct).compareTo(HUNDRED) > 0) {
                    throw ApiException.unprocessable("ownership_exceeds_100", "Total active " + type
                            + " ownership would be " + others.add(pct).stripTrailingZeros().toPlainString() + "%");
                }
            }
        } else if (pct != null) {
            throw ApiException.unprocessable("invalid_percentage", "ownership_percentage only applies to ownership relationships");
        }
        if (("GUARDIAN".equals(type)) && !CustomerService.NATURAL_PERSON.contains(text(rel, "customer_type"))
                && !"TRUST".equals(text(rel, "customer_type"))) {
            throw ApiException.unprocessable("invalid_relationship", "A guardian must be a natural person or trust");
        }
        if (text(v, "signing_limit") != null && text(v, "limit_currency") == null) {
            throw ApiException.unprocessable("invalid_signing_limit", "limit_currency is required with signing_limit");
        }
    }
}
