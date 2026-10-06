package io.meridian.archive.application;

import io.meridian.archive.domain.ArchiveRules;
import io.meridian.archive.domain.HoldMatcher;
import io.meridian.archive.infrastructure.JsonValues;
import io.meridian.archive.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.web.crud.CrudEngine;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Legal holds: placement (immediately blocking pending purges in scope), scope changes that may only widen,
 * maker-checker release (re-evaluating blocked purges) and the hold check used by other services.
 */
@Service
public class LegalHoldService {
    private final Rows rows;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    public LegalHoldService(Rows rows, CrudEngine crud, OutboxWriter outbox, TransactionTemplate tx, JsonMapper json) {
        this.rows = rows;
        this.crud = crud;
        this.outbox = outbox;
        this.tx = tx;
        this.json = json;
    }

    public List<HoldMatcher.Hold> activeHolds() {
        return rows.query("""
                select row_to_json(h)::text from legal_hold h where status = 'ACTIVE' and starts_on <= current_date
                order by created_at""").stream().map(LegalHoldService::hold).toList();
    }

    static HoldMatcher.Hold hold(JsonNode h) {
        return new HoldMatcher.Hold(h.get("id").asString(), h.path("name").asString(), h.get("scope_type").asString(),
                new LinkedHashSet<>(JsonValues.strings(h.get("scope_ids"))), JsonValues.text(h, "customer_id"));
    }

    public String place(ObjectNode v) {
        String scope = JsonValues.text(v, "scope_type");
        List<String> ids = JsonValues.strings(v.get("scope_ids"));
        if (!"TENANT".equals(scope) && ids.isEmpty()) {
            throw ApiException.unprocessable("scope_ids_required", "scope_ids must list the held " + scope + " ids");
        }
        if (v.hasNonNull("status") && !"ACTIVE".equals(v.get("status").asString())) {
            throw ApiException.unprocessable("invalid_status", "A legal hold is placed ACTIVE; release it with /release");
        }
        LocalDate starts = LocalDate.parse(v.get("starts_on").asString());
        if (v.hasNonNull("review_on") && LocalDate.parse(v.get("review_on").asString()).isBefore(starts)) {
            throw ApiException.unprocessable("invalid_review_date", "review_on must not be before starts_on");
        }
        if ("CUSTOMER".equals(scope) && !v.hasNonNull("customer_id") && ids.size() == 1) {
            try {
                v.put("customer_id", UUID.fromString(ids.getFirst()).toString());
            } catch (IllegalArgumentException ignored) {
                // non-UUID customer reference: keep only in scope_ids
            }
        }
        v.put("status", "ACTIVE");
        return tx.execute(s -> {
            String row = crud.insert("legal_hold", v);
            JsonNode n = json.readTree(row);
            outbox.publish("operations.legal_hold.placed", "legal_hold", UUID.fromString(n.get("id").asString()), n);
            if (!starts.isAfter(LocalDate.now())) {
                blockPendingPurges(hold(n));
            }
            return row;
        });
    }

    /** PATCH rules: status only via /release, released holds are frozen, scope may only widen. */
    public void prepareUpdate(JsonNode current, ObjectNode changes) {
        if ("RELEASED".equals(current.get("status").asString())) {
            throw ApiException.conflict("hold_released", "A released hold cannot be changed");
        }
        if (changes.hasNonNull("status") && !changes.get("status").asString().equals(current.get("status").asString())) {
            throw ApiException.unprocessable("use_action_endpoint", "Use /release to release a legal hold");
        }
        if (changes.hasNonNull("scope_type") && !changes.get("scope_type").asString().equals(current.get("scope_type").asString())) {
            throw ApiException.unprocessable("scope_type_immutable", "The scope type of a hold cannot change");
        }
        if (changes.has("scope_ids")) {
            Set<String> before = new HashSet<>(JsonValues.strings(current.get("scope_ids")));
            Set<String> after = new HashSet<>(JsonValues.strings(changes.get("scope_ids")));
            if (!after.containsAll(before)) {
                throw ApiException.unprocessable("hold_scope_reduction",
                        "Ids cannot be removed from an active hold; release it and place a new one");
            }
        }
    }

    public void afterUpdate(String row) {
        JsonNode n = json.readTree(row);
        if ("ACTIVE".equals(n.get("status").asString())) {
            blockPendingPurges(hold(n));
        }
    }

    /** Releases a hold; the releaser must differ from the user who placed it. */
    public String release(UUID id, String reason) {
        return tx.execute(s -> {
            JsonNode h = rows.require("legal_hold", id, true);
            ArchiveRules.requireHoldRelease(h.get("status").asString());
            if (Objects.equals(h.path("created_by").asString(null), TenantContext.principal())) {
                throw ApiException.unprocessable("four_eyes_violation",
                        "A legal hold must be released by a different user than the one who placed it");
            }
            String row = rows.updateReturning("""
                    update legal_hold set status = 'RELEASED', released_at = now(), released_by = ?, updated_by = ?,
                           reason = reason || E'\\nReleased: ' || ?
                    where id = ? returning row_to_json(legal_hold)::text""",
                    TenantContext.principal(), TenantContext.principal(), reason, id);
            outbox.publish("operations.legal_hold.released", "legal_hold", id, json.readTree(row));
            reevaluateBlockedBy(id);
            return row;
        });
    }

    /** Hold check for one entity: {@code {scope_type, id, on_hold, holds:[...]}}. */
    public JsonNode check(String scopeType, String id) {
        if (scopeType == null || id == null) {
            throw ApiException.badRequest("scope_required", "scope_type and id query parameters are required");
        }
        String scope = scopeType.toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("CUSTOMER", "ACCOUNT", "CASE", "TABLE", "DOCUMENT", "TENANT").contains(scope)) {
            throw ApiException.badRequest("invalid_scope_type", "Unknown scope_type " + scopeType);
        }
        List<HoldMatcher.Hold> matching = HoldMatcher.matching(activeHolds(), scope, id);
        ObjectNode out = json.createObjectNode();
        out.put("scope_type", scope);
        out.put("id", id);
        out.put("on_hold", !matching.isEmpty());
        var arr = out.putArray("holds");
        matching.forEach(h -> arr.addObject().put("legal_hold_id", h.id()).put("name", h.name()).put("scope_type", h.scopeType()));
        return out;
    }

    /** Selector ids by hold scope (customer_ids, account_ids, case_ids, document_ids). */
    public static Map<String, Set<String>> selectorIds(JsonNode selector) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        HoldMatcher.SELECTOR_FIELDS.forEach((scope, field) -> {
            List<String> ids = JsonValues.strings(selector == null ? null : selector.get(field));
            if (!ids.isEmpty()) {
                out.put(scope, new LinkedHashSet<>(ids));
            }
        });
        return out;
    }

    private void blockPendingPurges(HoldMatcher.Hold hold) {
        for (JsonNode p : rows.query("""
                select row_to_json(p)::text from purge_request p where status in ('PENDING_APPROVAL', 'APPROVED')""")) {
            if (decide(List.of(hold), p).blocked()) {
                rows.update("update purge_request set status = 'BLOCKED_BY_HOLD', blocked_by_hold_id = ?::uuid where id = ?",
                        hold.id(), UUID.fromString(p.get("id").asString()));
            }
        }
    }

    private void reevaluateBlockedBy(UUID holdId) {
        List<HoldMatcher.Hold> remaining = activeHolds();
        for (JsonNode p : rows.query("select row_to_json(p)::text from purge_request p where blocked_by_hold_id = ?", holdId)) {
            if (!"BLOCKED_BY_HOLD".equals(p.get("status").asString())) {
                continue;
            }
            HoldMatcher.Decision d = decide(remaining, p);
            UUID pid = UUID.fromString(p.get("id").asString());
            if (d.blocked()) {
                rows.update("update purge_request set blocked_by_hold_id = ?::uuid where id = ?", d.blockedBy().id(), pid);
            } else {
                // circumstances changed while blocked: the purge needs a fresh approval
                rows.update("update purge_request set status = 'PENDING_APPROVAL', blocked_by_hold_id = null where id = ?", pid);
            }
        }
    }

    HoldMatcher.Decision decide(List<HoldMatcher.Hold> holds, JsonNode purge) {
        String db = "";
        String table = "";
        if (purge.hasNonNull("data_classification_id")) {
            JsonNode c = rows.require("data_classification", UUID.fromString(purge.get("data_classification_id").asString()), false);
            db = c.get("database_name").asString();
            table = c.get("table_name").asString();
        }
        return HoldMatcher.decide(holds, db, table, selectorIds(purge.get("record_selector")));
    }
}
