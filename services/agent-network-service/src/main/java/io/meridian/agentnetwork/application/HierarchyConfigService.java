package io.meridian.agentnetwork.application;

import io.meridian.agentnetwork.domain.Hierarchy;
import io.meridian.agentnetwork.infrastructure.Rows;
import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * WE-HIER-1 (G-06): the configurable distribution hierarchy — {@code distribution_domain} and n-level
 * {@code domain_category}. Codes are immutable; a category derives its level from its parent, the single level-1
 * category is the domain owner, and the parent chain is cycle-guarded. Each row goes through the same maker-checker
 * lifecycle as a commission plan: DRAFT -&gt; PENDING_APPROVAL (submit) -&gt; ACTIVE (approve, four-eyes) -&gt; RETIRED,
 * routed centrally to workflow-service and applied by {@link DistributionDomainApprovalHandler} /
 * {@link DomainCategoryApprovalHandler}.
 */
@Service
public class HierarchyConfigService {
    public static final String DOMAIN = "distribution_domain";
    public static final String CATEGORY = "domain_category";

    /** Approval resource types in workflow-service (SEC-03 owner = agent-network-service). */
    public static final String DOMAIN_RESOURCE = "distribution_domain";
    public static final String CATEGORY_RESOURCE = "domain_category";

    private final Rows rows;
    private final Events events;
    private final Approvals approvals;

    public HierarchyConfigService(Rows rows, Events events, Approvals approvals) {
        this.rows = rows;
        this.events = events;
        this.approvals = approvals;
    }

    // ------------------------------------------------------------------ distribution_domain

    @Transactional
    public ObjectNode createDomain(JsonNode req) {
        ObjectNode v = rows.columns(DOMAIN, req);
        requireUniqueCode(DOMAIN, Rows.str(v, "code"), null);
        v.put("status", "DRAFT");
        return rows.insert(DOMAIN, v);
    }

    // ------------------------------------------------------------------ domain_category

    @Transactional
    public ObjectNode createCategory(JsonNode req) {
        ObjectNode v = rows.columns(CATEGORY, req);
        UUID domainId = Rows.uuid(v, "distribution_domain_id");
        ObjectNode domain = rows.find(DOMAIN, domainId).orElseThrow(() ->
                ApiException.unprocessable("domain_not_found", "The distribution domain does not exist"));
        UUID parentId = Rows.uuid(v, "parent_category_id");
        ObjectNode parent = parentId == null ? null : rows.find(CATEGORY, parentId).orElseThrow(() ->
                ApiException.unprocessable("parent_not_found", "The parent category does not exist"));
        boolean ownerExists = rows.scalar(
                "select count(*) from domain_category where distribution_domain_id = ? and is_owner", Long.class, domainId)
                .orElse(0L) > 0;
        int level = Hierarchy.validateCategoryParent(domainId, parentId,
                parent == null ? null : Rows.uuid(parent, "distribution_domain_id"),
                parent == null ? null : Rows.integer(parent, "level"),
                parent == null ? null : Rows.str(parent, "status"), ownerExists);
        requireUniqueCode(CATEGORY, Rows.str(v, "code"), domainId);
        v.put("level", level);
        v.put("is_owner", level == 1);
        v.put("status", "DRAFT");
        return rows.insert(CATEGORY, v);
    }

    /** The domain's category tree: {domain, categories:[{category, children[]}]}. */
    public ObjectNode domainTree(UUID domainId) {
        ObjectNode domain = rows.get(DOMAIN, domainId);
        List<ObjectNode> all = rows.query(
                "select row_to_json(c)::text from domain_category c where c.distribution_domain_id = ? order by c.level, c.priority, c.code",
                domainId);
        ObjectNode out = rows.object();
        out.set("domain", strip(domain));
        out.set("categories", forest(null, byParent(all)));
        return out;
    }

    /** The subtree rooted at one category: {category, children[]}. */
    public ObjectNode categoryTree(UUID categoryId) {
        ObjectNode category = rows.get(CATEGORY, categoryId);
        List<ObjectNode> all = rows.query("""
                with recursive sub as (
                  select c.*, 0 as depth from domain_category c where c.id = ?
                  union all
                  select c.*, sub.depth + 1 from domain_category c join sub on c.parent_category_id = sub.id where sub.depth < 50)
                select row_to_json(x)::text from (select * from sub order by level, priority, code) x""", categoryId);
        Map<String, List<ObjectNode>> byParent = byParent(all);
        ObjectNode out = rows.object();
        out.set("category", strip(category));
        out.set("children", forest(categoryId.toString(), byParent));
        return out;
    }

    // ------------------------------------------------------------------ generic maker-checker lifecycle

    @Transactional
    public ObjectNode submit(String table, UUID id) {
        ObjectNode r = rows.lock(table, id);
        Hierarchy.requireConfigTransition(Rows.str(r, "status"), "PENDING_APPROVAL");
        ObjectNode v = rows.object();
        v.put("status", "PENDING_APPROVAL");
        v.put("submitted_by", TenantContext.principal());
        v.putNull("rejection_reason");
        rows.update(table, id, v);
        rows.execute("update " + table + " set submitted_at = now() where id = ?", id);
        ObjectNode after = rows.get(table, id);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", Rows.str(after, "code"));
        payload.put("name", Rows.str(after, "name"));
        payload.put("submitted_by", Rows.str(after, "submitted_by"));
        payload.put("submitted_at", Rows.str(after, "submitted_at"));
        JsonNode req = approvals.request(ApprovalSpec.of(resource(table), id, "approve",
                        label(table) + " " + Rows.str(after, "code"), payload),
                "submit:" + id + ":" + Rows.str(after, "submitted_at"));
        UUID rid = Approvals.idOf(req);
        if (rid != null) {
            rows.execute("update " + table + " set approval_request_id = ? where id = ?", rid, id);
            return rows.get(table, id);
        }
        return after;
    }

    @Transactional
    public ObjectNode approve(String table, UUID id) {
        ObjectNode r = rows.lock(table, id);
        requirePending(r);
        if (TenantContext.principal().equals(Rows.str(r, "submitted_by"))) {
            throw ApiException.unprocessable("four_eyes_violation",
                    "The " + label(table).toLowerCase() + " must be approved by a different user than its submitter");
        }
        if (CATEGORY.equals(table)) {
            if (!"ACTIVE".equals(Rows.str(rows.get(DOMAIN, Rows.uuid(r, "distribution_domain_id")), "status"))) {
                throw ApiException.unprocessable("domain_not_active", "Activate the distribution domain first");
            }
            UUID parentId = Rows.uuid(r, "parent_category_id");
            if (parentId != null && !"ACTIVE".equals(Rows.str(rows.get(CATEGORY, parentId), "status"))) {
                throw ApiException.unprocessable("parent_not_active", "Activate the parent category first");
            }
        }
        ObjectNode v = rows.object();
        v.put("status", "ACTIVE");
        v.put("approved_by", TenantContext.principal());
        rows.update(table, id, v);
        rows.execute("update " + table + " set approved_at = now() where id = ?", id);
        ObjectNode after = rows.get(table, id);
        events.publish(DOMAIN.equals(table) ? "wallets.distribution_domain.activated" : "wallets.domain_category.activated",
                table, after);
        approvals.cancelOpen(resource(table), id, null, "approved directly");
        return after;
    }

    @Transactional
    public ObjectNode reject(String table, UUID id, String reason) {
        ObjectNode r = rows.lock(table, id);
        requirePending(r);
        ObjectNode v = rows.object();
        v.put("status", "DRAFT");
        v.put("rejection_reason", reason);
        ObjectNode after = rows.update(table, id, v);
        approvals.cancelOpen(resource(table), id, null, "rejected directly");
        return after;
    }

    @Transactional
    public ObjectNode retire(String table, UUID id) {
        ObjectNode r = rows.lock(table, id);
        String status = Rows.str(r, "status");
        if (!"ACTIVE".equals(status) && !"DRAFT".equals(status)) {
            throw ApiException.conflict("invalid_transition", "A " + status + " " + label(table).toLowerCase() + " cannot be retired");
        }
        if (DOMAIN.equals(table) && rows.scalar("""
                select count(*) from domain_category where distribution_domain_id = ? and status <> 'RETIRED'""",
                Long.class, id).orElse(0L) > 0) {
            throw ApiException.unprocessable("categories_present", "Retire the domain's categories first");
        }
        if (CATEGORY.equals(table) && rows.scalar("""
                select count(*) from domain_category where parent_category_id = ? and status <> 'RETIRED'""",
                Long.class, id).orElse(0L) > 0) {
            throw ApiException.unprocessable("child_categories_present", "Retire the child categories first");
        }
        ObjectNode v = rows.object();
        v.put("status", "RETIRED");
        rows.update(table, id, v);
        rows.execute("update " + table + " set retired_at = now() where id = ?", id);
        ObjectNode after = rows.get(table, id);
        events.publish(DOMAIN.equals(table) ? "wallets.distribution_domain.retired" : "wallets.domain_category.retired",
                table, after);
        approvals.cancelOpen(resource(table), id, null, "retired");
        return after;
    }

    /** True while the row is PENDING_APPROVAL from the same submission (submitted_at unchanged). */
    @Transactional(readOnly = true)
    public boolean awaitsApproval(String table, UUID id, String submittedAt) {
        return rows.find(table, id)
                .filter(r -> "PENDING_APPROVAL".equals(Rows.str(r, "status")))
                .filter(r -> submittedAt == null || submittedAt.equals(Rows.str(r, "submitted_at")))
                .isPresent();
    }

    /** PATCH guard: only DRAFT rows change; the code is permanent. */
    public void checkEditable(String table, UUID id, JsonNode patch) {
        ObjectNode r = rows.get(table, id);
        if (!"DRAFT".equals(Rows.str(r, "status"))) {
            throw ApiException.conflict("not_editable",
                    label(table) + " " + Rows.str(r, "code") + " is " + Rows.str(r, "status") + "; only a DRAFT can be edited");
        }
        if (patch.hasNonNull("code") && !patch.get("code").asString().equals(Rows.str(r, "code"))) {
            throw ApiException.unprocessable("field_not_updatable", "code cannot be changed");
        }
    }

    // ------------------------------------------------------------------ helpers

    private void requireUniqueCode(String table, String code, UUID domainId) {
        String sql = "select count(*) from " + table + " where code = ?" + (domainId == null ? "" : " and distribution_domain_id = ?");
        long n = (domainId == null ? rows.scalar(sql, Long.class, code) : rows.scalar(sql, Long.class, code, domainId)).orElse(0L);
        if (n > 0) {
            throw ApiException.conflict("duplicate_code", label(table) + " code " + code + " already exists");
        }
    }

    private Map<String, List<ObjectNode>> byParent(List<ObjectNode> all) {
        Map<String, List<ObjectNode>> byParent = new HashMap<>();
        for (ObjectNode c : all) {
            byParent.computeIfAbsent(Rows.str(c, "parent_category_id"), k -> new ArrayList<>()).add(c);
        }
        return byParent;
    }

    private ArrayNode forest(String parentKey, Map<String, List<ObjectNode>> byParent) {
        ArrayNode arr = rows.json().createArrayNode();
        for (ObjectNode c : byParent.getOrDefault(parentKey, List.of())) {
            ObjectNode n = arr.addObject();
            ObjectNode s = strip(c);
            s.remove("depth");
            n.set("category", s);
            n.set("children", forest(Rows.str(c, "id"), byParent));
        }
        return arr;
    }

    private static ObjectNode strip(ObjectNode row) {
        ObjectNode s = row.deepCopy();
        s.remove("tenant_id");
        return s;
    }

    private static void requirePending(JsonNode r) {
        if (!"PENDING_APPROVAL".equals(Rows.str(r, "status"))) {
            throw ApiException.conflict("invalid_transition", "The row is " + Rows.str(r, "status") + ", not PENDING_APPROVAL");
        }
    }

    static String resource(String table) {
        return DOMAIN.equals(table) ? DOMAIN_RESOURCE : CATEGORY_RESOURCE;
    }

    private static String label(String table) {
        return DOMAIN.equals(table) ? "Distribution domain" : "Domain category";
    }
}
