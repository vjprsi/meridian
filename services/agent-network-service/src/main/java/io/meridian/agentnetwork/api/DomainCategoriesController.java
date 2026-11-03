package io.meridian.agentnetwork.api;

import io.meridian.agentnetwork.api.generated.DomainCategoriesApi;
import io.meridian.agentnetwork.api.generated.model.CategoryServicePreferencePage;
import io.meridian.agentnetwork.api.generated.model.DomainCategoriesApproveRequest;
import io.meridian.agentnetwork.api.generated.model.DomainCategoriesRejectRequest;
import io.meridian.agentnetwork.api.generated.model.DomainCategory;
import io.meridian.agentnetwork.api.generated.model.DomainCategoryCreate;
import io.meridian.agentnetwork.api.generated.model.DomainCategoryUpdate;
import io.meridian.agentnetwork.application.HierarchyConfigService;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** WE-HIER-1: n-level domain categories with maker-checker, a category tree and service-preference listing. */
@RestController
public class DomainCategoriesController implements DomainCategoriesApi {
    private static final String TABLE = HierarchyConfigService.CATEGORY;

    private final HierarchyConfigService hierarchy;
    private final Actions actions;
    private final CrudEngine crud;

    public DomainCategoriesController(HierarchyConfigService hierarchy, Actions actions, CrudEngine crud) {
        this.hierarchy = hierarchy;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<DomainCategory> domainCategoriesCreate(String idempotencyKey, DomainCategoryCreate body) {
        return actions.create(idempotencyKey, body, DomainCategory.class, () -> hierarchy.createCategory(actions.tree(body)));
    }

    @Override
    public ResponseEntity<DomainCategory> domainCategoriesUpdate(UUID id, String ifMatch, DomainCategoryUpdate body) {
        hierarchy.checkEditable(TABLE, id, actions.tree(body));
        return actions.guardedUpdate(crud, TABLE, DomainCategory.class, id, ifMatch, body,
                Set.of("code", "distribution_domain_id", "parent_category_id"),
                "code, domain and parent are fixed; the status changes through submit/approve");
    }

    @Override
    public ResponseEntity<DomainCategory> domainCategoriesSubmit(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "submit", null, DomainCategory.class, () -> hierarchy.submit(TABLE, id));
    }

    @Override
    public ResponseEntity<DomainCategory> domainCategoriesApprove(UUID id, String idempotencyKey,
                                                                  DomainCategoriesApproveRequest body) {
        return actions.action(idempotencyKey, id, "approve", body, DomainCategory.class, () -> hierarchy.approve(TABLE, id));
    }

    @Override
    public ResponseEntity<DomainCategory> domainCategoriesReject(UUID id, String idempotencyKey,
                                                                 DomainCategoriesRejectRequest body) {
        return actions.action(idempotencyKey, id, "reject", body, DomainCategory.class,
                () -> hierarchy.reject(TABLE, id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<DomainCategory> domainCategoriesRetire(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "retire", null, DomainCategory.class, () -> hierarchy.retire(TABLE, id));
    }

    @Override
    public ResponseEntity<Map<String, Object>> domainCategoriesCategories(UUID id) {
        TenantContext.require();
        return actions.object(hierarchy.categoryTree(id));
    }

    @Override
    public ResponseEntity<CategoryServicePreferencePage> domainCategoriesServicePreferences(UUID id, Integer limit, String cursor) {
        crud.get(TABLE, DomainCategory.class, Crud.params().with("id", id));
        return crud.list("category_service_preference", CategoryServicePreferencePage.class,
                Crud.params().with("domain_category_id", id).with("limit", limit).with("cursor", cursor));
    }
}
