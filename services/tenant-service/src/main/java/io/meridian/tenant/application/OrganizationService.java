package io.meridian.tenant.application;

import io.meridian.common.error.ApiException;
import io.meridian.tenant.domain.Lifecycle;
import io.meridian.tenant.domain.Validators;
import io.meridian.tenant.infrastructure.TenantStore;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Legal entities and branches: validation, group structure, branch closure and their domain events. */
@Service
public class OrganizationService {
    private final TenantStore store;
    private final CrudEngine crud;
    private final Events events;
    private final JsonMapper json;

    public OrganizationService(TenantStore store, CrudEngine crud, Events events, JsonMapper json) {
        this.store = store;
        this.crud = crud;
        this.events = events;
        this.json = json;
    }

    // ------------------------------------------------------------------ legal entities

    @Transactional
    public JsonNode createLegalEntity(Object body) {
        ObjectNode v = crud.toColumns("legal_entity", body);
        if (v.hasNonNull("status") && !"ACTIVE".equals(v.get("status").asString())) {
            throw ApiException.unprocessable("invalid_initial_status", "A legal entity is created ACTIVE");
        }
        validateLegalEntity(v);
        if (v.hasNonNull("parent_id")) {
            ObjectNode parent = store.find("legal_entity", UUID.fromString(v.get("parent_id").asString()))
                    .orElseThrow(() -> ApiException.unprocessable("parent_not_found", "Parent legal entity does not exist"));
            if ("DISSOLVED".equals(parent.get("status").asString())) {
                throw ApiException.unprocessable("parent_dissolved", "Parent legal entity is dissolved");
            }
        }
        ObjectNode row = store.insert("legal_entity", v);
        events.publish("platform.legal_entity.created", "legal_entity", UUID.fromString(row.get("id").asString()), row);
        return row;
    }

    @Transactional
    public <T> ResponseEntity<T> updateLegalEntity(UUID id, Object body, String ifMatch, Class<T> type) {
        ObjectNode v = crud.toColumns("legal_entity", body);
        ObjectNode current = store.lock("legal_entity", id);
        validateLegalEntity(v);
        String from = current.get("status").asString();
        if (v.hasNonNull("status") && !from.equals(v.get("status").asString())) {
            String to = v.get("status").asString();
            Lifecycle.require(Lifecycle.LEGAL_ENTITY, "Legal entity", from, to);
            if (!"ACTIVE".equals(to)) {
                long openBranches = store.query("select row_to_json(b)::text from branch b where legal_entity_id = ? and status <> 'CLOSED'", id).size();
                if (openBranches > 0 && "DISSOLVED".equals(to)) {
                    throw ApiException.unprocessable("legal_entity_has_open_branches",
                            "Close the entity's " + openBranches + " open branches before dissolving it");
                }
            }
        } else if ("DISSOLVED".equals(from)) {
            throw ApiException.conflict("legal_entity_dissolved", "A dissolved legal entity cannot be changed");
        }
        if (v.hasNonNull("parent_id")) {
            requireNoCycle(id, UUID.fromString(v.get("parent_id").asString()));
        }
        ResponseEntity<T> r = crud.update("legal_entity", type, body, ifMatch, Crud.params().with("id", id));
        events.publish("platform.legal_entity.updated", "legal_entity", id, json.valueToTree(r.getBody()));
        return r;
    }

    private void validateLegalEntity(ObjectNode v) {
        if (v.hasNonNull("lei") && !Validators.isValidLei(v.get("lei").asString())) {
            throw ApiException.unprocessable("invalid_lei", "lei is not a valid ISO 17442 Legal Entity Identifier");
        }
        if (v.hasNonNull("swift_bic") && !Validators.isValidBic(v.get("swift_bic").asString())) {
            throw ApiException.unprocessable("invalid_bic", "swift_bic is not a valid ISO 9362 BIC");
        }
        if (v.has("fiscal_year_start_month")) {
            int m = v.get("fiscal_year_start_month").asInt();
            if (m < 1 || m > 12) {
                throw ApiException.unprocessable("invalid_fiscal_year_start_month", "fiscal_year_start_month must be 1-12");
            }
        }
        if (v.hasNonNull("base_currency") && !Validators.isCurrency(v.get("base_currency").asString())) {
            throw ApiException.unprocessable("invalid_currency", "base_currency must be an ISO 4217 code");
        }
        if (v.hasNonNull("timezone")) {
            try {
                java.time.ZoneId.of(v.get("timezone").asString());
            } catch (java.time.DateTimeException e) {
                throw ApiException.unprocessable("invalid_timezone", "timezone must be an IANA timezone id");
            }
        }
    }

    private void requireNoCycle(UUID id, UUID parentId) {
        Set<UUID> seen = new HashSet<>();
        UUID cur = parentId;
        while (cur != null) {
            if (cur.equals(id)) {
                throw ApiException.unprocessable("parent_cycle", "A legal entity cannot be its own ancestor");
            }
            if (!seen.add(cur)) {
                break;
            }
            ObjectNode p = store.find("legal_entity", cur)
                    .orElseThrow(() -> ApiException.unprocessable("parent_not_found", "Parent legal entity does not exist"));
            cur = p.hasNonNull("parent_id") ? UUID.fromString(p.get("parent_id").asString()) : null;
        }
    }

    // ------------------------------------------------------------------ branches

    @Transactional
    public JsonNode createBranch(Object body) {
        ObjectNode v = crud.toColumns("branch", body);
        String status = v.path("status").asString("ACTIVE");
        if (!Set.of("ACTIVE", "TEMPORARILY_CLOSED").contains(status)) {
            throw ApiException.unprocessable("invalid_initial_status", "A branch is created ACTIVE or TEMPORARILY_CLOSED; close it with /close");
        }
        UUID legalEntityId = UUID.fromString(v.get("legal_entity_id").asString());
        requireActiveLegalEntity(legalEntityId);
        validateBranch(v, legalEntityId, null);
        ObjectNode row = store.insert("branch", v);
        events.publish("platform.branch.created", "branch", UUID.fromString(row.get("id").asString()), row);
        return row;
    }

    @Transactional
    public <T> ResponseEntity<T> updateBranch(UUID id, Object body, String ifMatch, Class<T> type) {
        ObjectNode v = crud.toColumns("branch", body);
        ObjectNode current = store.lock("branch", id);
        String from = current.get("status").asString();
        if ("CLOSED".equals(from)) {
            throw ApiException.conflict("branch_closed", "A closed branch cannot be changed");
        }
        if (v.hasNonNull("status") && !from.equals(v.get("status").asString())) {
            String to = v.get("status").asString();
            if ("CLOSED".equals(to)) {
                throw ApiException.conflict("status_change_requires_action", "Close a branch with /close");
            }
            Lifecycle.require(Lifecycle.BRANCH, "Branch", from, to);
        }
        UUID legalEntityId = v.hasNonNull("legal_entity_id") ? UUID.fromString(v.get("legal_entity_id").asString())
                : UUID.fromString(current.get("legal_entity_id").asString());
        if (v.hasNonNull("legal_entity_id")) {
            requireActiveLegalEntity(legalEntityId);
        }
        validateBranch(v, legalEntityId, id);
        ResponseEntity<T> r = crud.update("branch", type, body, ifMatch, Crud.params().with("id", id));
        events.publish("platform.branch.updated", "branch", id, json.valueToTree(r.getBody()));
        return r;
    }

    @Transactional
    public JsonNode closeBranch(UUID id, LocalDate closedOn, String reason) {
        ObjectNode b = store.lock("branch", id);
        Lifecycle.require(Lifecycle.BRANCH, "Branch", b.get("status").asString(), "CLOSED");
        if (b.hasNonNull("opened_on") && closedOn.isBefore(LocalDate.parse(b.get("opened_on").asString()))) {
            throw ApiException.unprocessable("invalid_closed_on", "closed_on cannot be before opened_on");
        }
        if (closedOn.isAfter(LocalDate.now().plusYears(1))) {
            throw ApiException.unprocessable("invalid_closed_on", "closed_on cannot be more than a year ahead");
        }
        int openChildren = store.query("select row_to_json(c)::text from branch c where parent_id = ? and status <> 'CLOSED'", id).size();
        if (openChildren > 0) {
            throw ApiException.unprocessable("branch_has_open_children",
                    "Close or re-parent the " + openChildren + " units under this branch first");
        }
        ObjectNode values = json.createObjectNode();
        values.put("status", "CLOSED");
        values.put("closed_on", closedOn.toString());
        values.put("close_reason", reason);
        ObjectNode row = store.update("branch", id, values);
        events.publish("platform.branch.closed", "branch", id, row);
        return row;
    }

    private void requireActiveLegalEntity(UUID legalEntityId) {
        ObjectNode le = store.find("legal_entity", legalEntityId)
                .orElseThrow(() -> ApiException.unprocessable("legal_entity_not_found", "Legal entity " + legalEntityId + " does not exist"));
        if (!"ACTIVE".equals(le.get("status").asString())) {
            throw ApiException.unprocessable("legal_entity_not_active", "Legal entity is " + le.get("status").asString());
        }
    }

    private void validateBranch(ObjectNode v, UUID legalEntityId, UUID selfId) {
        if (v.hasNonNull("parent_id")) {
            UUID parentId = UUID.fromString(v.get("parent_id").asString());
            if (parentId.equals(selfId)) {
                throw ApiException.unprocessable("parent_cycle", "A branch cannot be its own parent");
            }
            ObjectNode parent = store.find("branch", parentId)
                    .orElseThrow(() -> ApiException.unprocessable("parent_not_found", "Parent branch does not exist"));
            if (!legalEntityId.toString().equals(parent.get("legal_entity_id").asString())) {
                throw ApiException.unprocessable("parent_other_legal_entity", "Parent branch belongs to another legal entity");
            }
            if ("CLOSED".equals(parent.get("status").asString())) {
                throw ApiException.unprocessable("parent_closed", "Parent branch is closed");
            }
        }
        checkRange(v, "latitude", 90);
        checkRange(v, "longitude", 180);
    }

    private static void checkRange(ObjectNode v, String field, int bound) {
        if (v.hasNonNull(field)) {
            BigDecimal d = new BigDecimal(v.get(field).asString());
            if (d.abs().compareTo(BigDecimal.valueOf(bound)) > 0) {
                throw ApiException.unprocessable("invalid_" + field, field + " must be within +/-" + bound);
            }
        }
    }
}
