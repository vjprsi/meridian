package io.meridian.agentnetwork.api;

import io.meridian.agentnetwork.api.generated.DistributionDomainsApi;
import io.meridian.agentnetwork.api.generated.model.DistributionDomain;
import io.meridian.agentnetwork.api.generated.model.DistributionDomainCreate;
import io.meridian.agentnetwork.api.generated.model.DistributionDomainUpdate;
import io.meridian.agentnetwork.api.generated.model.DistributionDomainsApproveRequest;
import io.meridian.agentnetwork.api.generated.model.DistributionDomainsRejectRequest;
import io.meridian.agentnetwork.application.HierarchyConfigService;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.CrudEngine;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** WE-HIER-1: distribution domains (configurable hierarchy top level) with maker-checker. */
@RestController
public class DistributionDomainsController implements DistributionDomainsApi {
    private static final String TABLE = HierarchyConfigService.DOMAIN;

    private final HierarchyConfigService hierarchy;
    private final Actions actions;
    private final CrudEngine crud;

    public DistributionDomainsController(HierarchyConfigService hierarchy, Actions actions, CrudEngine crud) {
        this.hierarchy = hierarchy;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<DistributionDomain> distributionDomainsCreate(String idempotencyKey, DistributionDomainCreate body) {
        return actions.create(idempotencyKey, body, DistributionDomain.class, () -> hierarchy.createDomain(actions.tree(body)));
    }

    @Override
    public ResponseEntity<DistributionDomain> distributionDomainsUpdate(UUID id, String ifMatch, DistributionDomainUpdate body) {
        hierarchy.checkEditable(TABLE, id, actions.tree(body));
        return actions.guardedUpdate(crud, TABLE, DistributionDomain.class, id, ifMatch, body, Set.of("code"),
                "domain codes are permanent; the status changes through submit/approve");
    }

    @Override
    public ResponseEntity<DistributionDomain> distributionDomainsSubmit(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "submit", null, DistributionDomain.class, () -> hierarchy.submit(TABLE, id));
    }

    @Override
    public ResponseEntity<DistributionDomain> distributionDomainsApprove(UUID id, String idempotencyKey,
                                                                         DistributionDomainsApproveRequest body) {
        return actions.action(idempotencyKey, id, "approve", body, DistributionDomain.class, () -> hierarchy.approve(TABLE, id));
    }

    @Override
    public ResponseEntity<DistributionDomain> distributionDomainsReject(UUID id, String idempotencyKey,
                                                                        DistributionDomainsRejectRequest body) {
        return actions.action(idempotencyKey, id, "reject", body, DistributionDomain.class,
                () -> hierarchy.reject(TABLE, id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<DistributionDomain> distributionDomainsRetire(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "retire", null, DistributionDomain.class, () -> hierarchy.retire(TABLE, id));
    }

    @Override
    public ResponseEntity<Map<String, Object>> distributionDomainsCategories(UUID id) {
        TenantContext.require();
        return actions.object(hierarchy.domainTree(id));
    }
}
