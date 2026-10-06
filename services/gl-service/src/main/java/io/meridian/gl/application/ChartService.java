package io.meridian.gl.application;

import io.meridian.common.error.ApiException;
import io.meridian.gl.infrastructure.GlStore;
import io.meridian.web.crud.CrudEngine;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Chart of accounts: a tree per legal entity. Headers (non-postable) group accounts; only leaves accept postings.
 * A child lives in the same legal entity and accounting class as its parent, and inherits its currency restriction.
 * {@code level} is always derived from the tree.
 */
@Service
public class ChartService {
    private static final String T = "gl_account";

    private final GlStore store;
    private final CrudEngine crud;

    public ChartService(GlStore store, CrudEngine crud) {
        this.store = store;
        this.crud = crud;
    }

    @Transactional
    public String create(Object body) {
        ObjectNode v = crud.toColumns(T, body);
        UUID le = Fields.requireUuid(v, "legal_entity_id");
        String cls = Fields.requireText(v, "account_class");
        String status = v.hasNonNull("status") ? v.get("status").asString() : "ACTIVE";
        if ("CLOSED".equals(status)) {
            throw ApiException.unprocessable("invalid_initial_status", "A GL account cannot be created CLOSED");
        }
        int level = 1;
        UUID parentId = Fields.uuid(v, "parent_id");
        if (parentId != null) {
            JsonNode parent = validateParent(parentId, le, cls, null);
            level = parent.get("level").asInt() + 1;
            inheritCurrency(parent, v);
        }
        v.put("level", level);
        v.put("status", status);
        if (v.path("is_retained_earnings").asBoolean(false)) {
            validateRetainedEarnings(le, cls, v.path("is_postable").asBoolean(true), null);
        }
        return crud.insert(T, v);
    }

    @Transactional
    public String update(UUID id, String ifMatch, Object body) {
        int version = Fields.ifMatch(ifMatch);
        JsonNode cur = store.requireLocked(T, id);
        ObjectNode v = crud.toColumns(T, body);
        v.remove("level");
        UUID le = UUID.fromString(cur.get("legal_entity_id").asString());
        if (v.hasNonNull("legal_entity_id") && !le.equals(Fields.uuid(v, "legal_entity_id"))) {
            throw ApiException.unprocessable("legal_entity_immutable", "A GL account cannot move to another legal entity");
        }
        boolean children = hasChildren(id);
        boolean postings = hasPostings(id);

        String curStatus = cur.get("status").asString();
        String newStatus = v.hasNonNull("status") ? v.get("status").asString() : curStatus;
        if (!curStatus.equals(newStatus)) {
            if ("CLOSED".equals(curStatus)) {
                throw ApiException.conflict("invalid_transition", "A CLOSED GL account cannot be reactivated");
            }
            if ("CLOSED".equals(newStatus)) {
                if (store.count("select count(*) from gl_account where parent_id = ? and status <> 'CLOSED'", id) > 0) {
                    throw ApiException.unprocessable("account_has_children", "Close the child accounts first");
                }
                if (store.count("""
                        select count(*) from gl_balance b join accounting_period p on p.id = b.accounting_period_id
                        where b.gl_account_id = ? and p.status <> 'CLOSED' and b.closing_balance <> 0""", id) > 0) {
                    throw ApiException.unprocessable("account_not_empty",
                            "The account has a non-zero balance in a period that is not closed");
                }
            }
        }

        String curClass = cur.get("account_class").asString();
        String cls = v.hasNonNull("account_class") ? v.get("account_class").asString() : curClass;
        if (!cls.equals(curClass) && (children || postings)) {
            throw ApiException.unprocessable("account_in_use",
                    "The accounting class cannot change once the account has children or postings");
        }
        boolean curPostable = cur.get("is_postable").asBoolean();
        boolean postable = v.hasNonNull("is_postable") ? v.get("is_postable").asBoolean() : curPostable;
        if (curPostable && !postable && postings) {
            throw ApiException.unprocessable("account_has_postings", "An account with postings cannot become a header");
        }
        if (!curPostable && postable && children) {
            throw ApiException.unprocessable("account_has_children", "A header with children cannot become postable");
        }

        boolean retained = v.hasNonNull("is_retained_earnings") ? v.get("is_retained_earnings").asBoolean()
                : cur.path("is_retained_earnings").asBoolean(false);
        if (retained) {
            validateRetainedEarnings(le, cls, postable, id);
        }

        UUID curParent = Fields.uuid(cur, "parent_id");
        UUID newParent = v.hasNonNull("parent_id") ? Fields.uuid(v, "parent_id") : curParent;
        boolean levelChanged = false;
        if (newParent != null && (!Objects.equals(newParent, curParent) || !cls.equals(curClass))) {
            JsonNode parent = validateParent(newParent, le, cls, id);
            int level = parent.get("level").asInt() + 1;
            levelChanged = level != cur.get("level").asInt();
            v.put("level", level);
        }

        String row = crud.updateRow(T, Map.of("id", id), v, version)
                .orElseThrow(() -> ApiException.conflict("version_conflict",
                        "If-Match " + version + " does not match the current version; reload and retry"));
        if (levelChanged) {
            store.update("""
                    with recursive tree as (
                        select id, level from gl_account where id = ?
                        union all
                        select c.id, tree.level + 1 from gl_account c join tree on c.parent_id = tree.id)
                    update gl_account g set level = tree.level from tree
                    where g.id = tree.id and g.id <> ? and g.level <> tree.level""", id, id);
        }
        return row;
    }

    /** The retained-earnings designation: a postable EQUITY account, at most one per legal entity. */
    private void validateRetainedEarnings(UUID legalEntityId, String accountClass, boolean postable, UUID self) {
        if (!"EQUITY".equals(accountClass) || !postable) {
            throw ApiException.unprocessable("invalid_retained_earnings_account",
                    "The retained-earnings account must be a postable EQUITY account");
        }
        store.advisoryLock("gl_retained_earnings:" + legalEntityId);
        store.row("""
                select row_to_json(a)::text from gl_account a
                where a.legal_entity_id = ? and a.is_retained_earnings and (?::uuid is null or a.id <> ?::uuid)""",
                legalEntityId, self, self).ifPresent(other -> {
                    throw ApiException.conflict("retained_earnings_already_designated", "GL account "
                            + other.get("code").asString() + " is already the retained-earnings account of the legal entity");
                });
    }

    private JsonNode validateParent(UUID parentId, UUID legalEntityId, String accountClass, UUID self) {
        JsonNode parent = store.lock(T, parentId).orElseThrow(() ->
                ApiException.unprocessable("parent_not_found", "Parent GL account " + parentId + " does not exist"));
        if (!legalEntityId.toString().equals(parent.get("legal_entity_id").asString())) {
            throw ApiException.unprocessable("parent_legal_entity_mismatch",
                    "The parent account belongs to another legal entity");
        }
        if (parent.get("is_postable").asBoolean()) {
            throw ApiException.unprocessable("parent_is_postable",
                    "The parent must be a header (non-postable) account; postable accounts are leaves");
        }
        if (!"ACTIVE".equals(parent.get("status").asString())) {
            throw ApiException.unprocessable("parent_inactive", "The parent account is not ACTIVE");
        }
        if (!accountClass.equals(parent.get("account_class").asString())) {
            throw ApiException.unprocessable("account_class_mismatch",
                    "A child account must have the parent's accounting class " + parent.get("account_class").asString());
        }
        if (self != null && store.count("""
                with recursive anc as (
                    select id, parent_id from gl_account where id = ?
                    union all
                    select a.id, a.parent_id from gl_account a join anc on a.id = anc.parent_id)
                select count(*) from anc where id = ?""", parentId, self) > 0) {
            throw ApiException.unprocessable("hierarchy_cycle", "An account cannot be placed below itself");
        }
        return parent;
    }

    private static void inheritCurrency(JsonNode parent, ObjectNode child) {
        String pc = Fields.text(parent, "currency");
        if (pc == null) {
            return;
        }
        String cc = Fields.text(child, "currency");
        if (cc == null) {
            child.put("currency", pc.trim());
        } else if (!pc.trim().equals(cc)) {
            throw ApiException.unprocessable("currency_mismatch", "The parent account is restricted to " + pc.trim());
        }
    }

    private boolean hasChildren(UUID id) {
        return store.count("select count(*) from gl_account where parent_id = ?", id) > 0;
    }

    private boolean hasPostings(UUID id) {
        return store.count("""
                select (select count(*) from gl_journal_line where gl_account_id = ?)
                     + (select count(*) from gl_balance where gl_account_id = ?)""", id, id) > 0;
    }
}
