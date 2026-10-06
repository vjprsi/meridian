package io.meridian.alm.api;

import io.meridian.alm.api.generated.AlmBehaviouralAssumptionsApi;
import io.meridian.alm.api.generated.model.AlmBehaviouralAssumption;
import io.meridian.alm.api.generated.model.AlmBehaviouralAssumptionCreate;
import io.meridian.alm.api.generated.model.AlmBehaviouralAssumptionUpdate;
import io.meridian.alm.api.generated.model.AlmBehaviouralAssumptionsApproveRequest;
import io.meridian.alm.application.AssumptionService;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

/** Behavioural assumptions: validated parameters, maker-checker approval, frozen once approved. */
@RestController
public class AlmBehaviouralAssumptionsController implements AlmBehaviouralAssumptionsApi {
    private final AssumptionService service;
    private final Actions actions;
    private final CrudEngine crud;

    public AlmBehaviouralAssumptionsController(AssumptionService service, Actions actions, CrudEngine crud) {
        this.service = service;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<AlmBehaviouralAssumption> almBehaviouralAssumptionsCreate(String idempotencyKey,
                                                                                    AlmBehaviouralAssumptionCreate body) {
        TenantContext.require();
        ObjectNode values = service.prepareCreate(body);
        return actions.create(idempotencyKey, body, AlmBehaviouralAssumption.class, () -> service.create(values));
    }

    @Override
    public ResponseEntity<AlmBehaviouralAssumption> almBehaviouralAssumptionsUpdate(UUID id, String ifMatch,
                                                                                    AlmBehaviouralAssumptionUpdate body) {
        TenantContext.require();
        service.checkUpdate(id, body);
        return crud.update("alm_behavioural_assumption", AlmBehaviouralAssumption.class, body, ifMatch,
                Crud.params().with("id", id));
    }

    @Override
    public ResponseEntity<AlmBehaviouralAssumption> almBehaviouralAssumptionsApprove(
            UUID id, String idempotencyKey, AlmBehaviouralAssumptionsApproveRequest body) {
        return actions.action(idempotencyKey, id, "approve", body, AlmBehaviouralAssumption.class,
                () -> service.approve(id, Http.text(actions.tree(body), "comment")));
    }
}
