package io.meridian.routing.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.routing.api.generated.RailCutoffsApi;
import io.meridian.routing.api.generated.model.RailCutoff;
import io.meridian.routing.api.generated.model.RailCutoffCreate;
import io.meridian.routing.api.generated.model.RailCutoffUpdate;
import io.meridian.routing.application.RailService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Rail cut-offs: generic CRUD with HH:MM / IANA timezone / weekday validation. */
@RestController
public class RailCutoffsController implements RailCutoffsApi {
    private final RailService rails;
    private final Actions actions;
    private final CrudEngine crud;

    public RailCutoffsController(RailService rails, Actions actions, CrudEngine crud) {
        this.rails = rails;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<RailCutoff> railCutoffsCreate(String idempotencyKey, RailCutoffCreate body) {
        TenantContext.require();
        rails.validateCutoff(actions.tree(body));
        return crud.create("rail_cutoff", RailCutoff.class, body, idempotencyKey, Crud.params());
    }

    @Override
    public ResponseEntity<RailCutoff> railCutoffsUpdate(UUID id, String ifMatch, RailCutoffUpdate body) {
        TenantContext.require();
        rails.validateCutoff(actions.tree(body));
        return crud.update("rail_cutoff", RailCutoff.class, body, ifMatch, Crud.params().with("id", id));
    }
}
