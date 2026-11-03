package io.meridian.agentnetwork.application;

import io.meridian.agentnetwork.domain.Hierarchy;
import io.meridian.agentnetwork.infrastructure.Rows;
import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * WE-HIER-1 (MQ-GRADE-1/2): grades within a category. Codes are immutable; exactly one grade per tenant is the
 * un-registered grade (default classification of an un-onboarded holder) and a referenced grade cannot be deleted.
 * Maker-checker lifecycle DRAFT -&gt; PENDING_APPROVAL -&gt; ACTIVE -&gt; RETIRED, applied centrally by
 * {@link GradeApprovalHandler}. {@link #unregistered()} lazily bootstraps the factory domain/category/grade so the hot
 * path never 404s.
 */
@Service
public class GradeService {
    static final String GRADE = "grade";
    static final String SERVICE = "grade_service";
    /** Approval resource type in workflow-service (SEC-03 owner = agent-network-service). */
    public static final String APPROVAL_RESOURCE = "grade";

    static final String FACTORY_CATEGORY_CODE = "DISTRIBUTION";
    static final String UNREGISTERED_CODE = "UNREGISTERED";

    private final Rows rows;
    private final Events events;
    private final Approvals approvals;

    public GradeService(Rows rows, Events events, Approvals approvals) {
        this.rows = rows;
        this.events = events;
        this.approvals = approvals;
    }

    @Transactional
    public ObjectNode create(JsonNode req) {
        ObjectNode v = rows.columns(GRADE, req);
        rows.find(HierarchyConfigService.CATEGORY, Rows.uuid(v, "domain_category_id")).orElseThrow(() ->
                ApiException.unprocessable("category_not_found", "The domain category does not exist"));
        if (rows.scalar("select count(*) from grade where code = ?", Long.class, Rows.str(v, "code")).orElse(0L) > 0) {
            throw ApiException.conflict("duplicate_code", "Grade code " + Rows.str(v, "code") + " already exists");
        }
        if (Rows.bool(v, "is_unregistered")) {
            requireNoUnregistered(null);
        }
        v.put("status", "DRAFT");
        return rows.insert(GRADE, v);
    }

    /** PATCH guard: only DRAFT grades change; the code is permanent; the un-registered flag stays a singleton. */
    public void checkEditable(UUID id, JsonNode patch) {
        ObjectNode g = rows.get(GRADE, id);
        if (!"DRAFT".equals(Rows.str(g, "status"))) {
            throw ApiException.conflict("not_editable",
                    "Grade " + Rows.str(g, "code") + " is " + Rows.str(g, "status") + "; only a DRAFT can be edited");
        }
        if (patch.hasNonNull("code") && !patch.get("code").asString().equals(Rows.str(g, "code"))) {
            throw ApiException.unprocessable("field_not_updatable", "code cannot be changed");
        }
        if (patch.path("is_unregistered").asBoolean(false) && !Rows.bool(g, "is_unregistered")) {
            requireNoUnregistered(id);
        }
    }

    @Transactional
    public void delete(UUID id) {
        ObjectNode g = rows.lock(GRADE, id);
        if (Rows.bool(g, "is_unregistered")) {
            throw ApiException.conflict("grade_in_use", "The un-registered grade cannot be deleted");
        }
        long agents = rows.scalar("select count(*) from agent where grade_id = ?", Long.class, id).orElse(0L);
        if (agents > 0) {
            throw ApiException.conflict("grade_in_use", "Grade is assigned to " + agents + " agent(s); reassign them first");
        }
        long services = rows.scalar("select count(*) from grade_service where grade_id = ?", Long.class, id).orElse(0L);
        if (services > 0) {
            throw ApiException.conflict("grade_in_use", "Remove the grade's " + services + " service(s) first");
        }
        rows.execute("delete from grade where id = ?", id);
    }

    // ------------------------------------------------------------------ lifecycle

    @Transactional
    public ObjectNode submit(UUID id) {
        ObjectNode g = rows.lock(GRADE, id);
        Hierarchy.requireConfigTransition(Rows.str(g, "status"), "PENDING_APPROVAL");
        ObjectNode v = rows.object();
        v.put("status", "PENDING_APPROVAL");
        v.put("submitted_by", TenantContext.principal());
        v.putNull("rejection_reason");
        rows.update(GRADE, id, v);
        rows.execute("update grade set submitted_at = now() where id = ?", id);
        ObjectNode after = rows.get(GRADE, id);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", Rows.str(after, "code"));
        payload.put("name", Rows.str(after, "name"));
        payload.put("domain_category_id", Rows.str(after, "domain_category_id"));
        payload.put("submitted_by", Rows.str(after, "submitted_by"));
        payload.put("submitted_at", Rows.str(after, "submitted_at"));
        JsonNode req = approvals.request(ApprovalSpec.of(APPROVAL_RESOURCE, id, "approve",
                "Grade " + Rows.str(after, "code"), payload), "submit:" + id + ":" + Rows.str(after, "submitted_at"));
        UUID rid = Approvals.idOf(req);
        if (rid != null) {
            rows.execute("update grade set approval_request_id = ? where id = ?", rid, id);
            return rows.get(GRADE, id);
        }
        return after;
    }

    @Transactional
    public ObjectNode approve(UUID id) {
        ObjectNode g = rows.lock(GRADE, id);
        requirePending(g);
        if (TenantContext.principal().equals(Rows.str(g, "submitted_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "The grade must be approved by a different user than its submitter");
        }
        if (!"ACTIVE".equals(Rows.str(rows.get(HierarchyConfigService.CATEGORY, Rows.uuid(g, "domain_category_id")), "status"))) {
            throw ApiException.unprocessable("category_not_active", "Activate the category first");
        }
        ObjectNode v = rows.object();
        v.put("status", "ACTIVE");
        v.put("approved_by", TenantContext.principal());
        rows.update(GRADE, id, v);
        rows.execute("update grade set approved_at = now() where id = ?", id);
        ObjectNode after = rows.get(GRADE, id);
        events.publish("wallets.grade.activated", GRADE, after);
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "approved directly");
        return after;
    }

    @Transactional
    public ObjectNode reject(UUID id, String reason) {
        ObjectNode g = rows.lock(GRADE, id);
        requirePending(g);
        ObjectNode v = rows.object();
        v.put("status", "DRAFT");
        v.put("rejection_reason", reason);
        ObjectNode after = rows.update(GRADE, id, v);
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "rejected directly");
        return after;
    }

    @Transactional
    public ObjectNode retire(UUID id) {
        ObjectNode g = rows.lock(GRADE, id);
        String status = Rows.str(g, "status");
        if (!"ACTIVE".equals(status) && !"DRAFT".equals(status)) {
            throw ApiException.conflict("invalid_transition", "A " + status + " grade cannot be retired");
        }
        if (Rows.bool(g, "is_unregistered")) {
            throw ApiException.conflict("grade_in_use", "The un-registered grade cannot be retired");
        }
        ObjectNode v = rows.object();
        v.put("status", "RETIRED");
        rows.update(GRADE, id, v);
        rows.execute("update grade set retired_at = now() where id = ?", id);
        ObjectNode after = rows.get(GRADE, id);
        events.publish("wallets.grade.retired", GRADE, after);
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "retired");
        return after;
    }

    @Transactional(readOnly = true)
    public boolean awaitsApproval(UUID id, String submittedAt) {
        return rows.find(GRADE, id)
                .filter(g -> "PENDING_APPROVAL".equals(Rows.str(g, "status")))
                .filter(g -> submittedAt == null || submittedAt.equals(Rows.str(g, "submitted_at")))
                .isPresent();
    }

    public List<ObjectNode> services(UUID gradeId) {
        rows.get(GRADE, gradeId);
        return rows.query("select row_to_json(s)::text from grade_service s where s.grade_id = ? order by s.priority, s.id", gradeId);
    }

    // ------------------------------------------------------------------ un-registered grade (lazy bootstrap)

    /**
     * The tenant's un-registered grade. If none exists it is bootstrapped together with the factory distribution
     * domain and owner category (all ACTIVE), so {@code GET /v1/grades/unregistered} never 404s on the hot path.
     */
    @Transactional
    public ObjectNode unregistered() {
        return rows.queryOne("select row_to_json(g)::text from grade g where g.is_unregistered limit 1")
                .orElseGet(this::bootstrapUnregistered);
    }

    private ObjectNode bootstrapUnregistered() {
        UUID domainId = rows.queryOne("select row_to_json(d)::text from distribution_domain d where d.code = ?",
                        Hierarchy.FACTORY_DOMAIN_CODE)
                .map(d -> Rows.uuid(d, "id"))
                .orElseGet(() -> {
                    ObjectNode d = rows.object();
                    d.put("code", Hierarchy.FACTORY_DOMAIN_CODE);
                    d.put("name", "Distribution");
                    d.put("description", "Factory distribution domain (bootstrapped)");
                    d.put("status", "ACTIVE");
                    return Rows.uuid(rows.insert(HierarchyConfigService.DOMAIN, d), "id");
                });
        UUID categoryId = rows.queryOne(
                        "select row_to_json(c)::text from domain_category c where c.distribution_domain_id = ? and c.is_owner",
                        domainId)
                .map(c -> Rows.uuid(c, "id"))
                .orElseGet(() -> {
                    ObjectNode c = rows.object();
                    c.put("distribution_domain_id", domainId.toString());
                    c.put("code", FACTORY_CATEGORY_CODE);
                    c.put("name", "Distribution");
                    c.put("level", 1);
                    c.put("is_owner", true);
                    c.put("status", "ACTIVE");
                    return Rows.uuid(rows.insert(HierarchyConfigService.CATEGORY, c), "id");
                });
        ObjectNode g = rows.object();
        g.put("domain_category_id", categoryId.toString());
        g.put("code", UNREGISTERED_CODE);
        g.put("name", "Un-registered");
        g.put("description", "Default grade of an un-onboarded holder (bootstrapped)");
        g.put("is_unregistered", true);
        g.put("status", "ACTIVE");
        return rows.insert(GRADE, g);
    }

    // ------------------------------------------------------------------ helpers

    private void requireNoUnregistered(UUID exceptId) {
        long n = exceptId == null
                ? rows.scalar("select count(*) from grade where is_unregistered", Long.class).orElse(0L)
                : rows.scalar("select count(*) from grade where is_unregistered and id <> ?", Long.class, exceptId).orElse(0L);
        if (n > 0) {
            throw ApiException.conflict("unregistered_exists", "The tenant already has an un-registered grade");
        }
    }

    private static void requirePending(JsonNode g) {
        if (!"PENDING_APPROVAL".equals(Rows.str(g, "status"))) {
            throw ApiException.conflict("invalid_transition", "The grade is " + Rows.str(g, "status") + ", not PENDING_APPROVAL");
        }
    }
}
