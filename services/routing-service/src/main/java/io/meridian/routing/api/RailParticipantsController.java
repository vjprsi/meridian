package io.meridian.routing.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.routing.api.generated.RailParticipantsApi;
import io.meridian.routing.api.generated.model.RailParticipant;
import io.meridian.routing.api.generated.model.RailParticipantCreate;
import io.meridian.routing.api.generated.model.RailParticipantUpdate;
import io.meridian.routing.api.generated.model.RailParticipantsLookupRequest;
import io.meridian.routing.application.ParticipantService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Bank directory (global): validated CRUD and lookup by BIC, IBAN or clearing id. */
@RestController
public class RailParticipantsController implements RailParticipantsApi {
    private final ParticipantService participants;
    private final Actions actions;
    private final CrudEngine crud;

    public RailParticipantsController(ParticipantService participants, Actions actions, CrudEngine crud) {
        this.participants = participants;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<RailParticipant> railParticipantsCreate(String idempotencyKey, RailParticipantCreate body) {
        TenantContext.require();
        participants.validate(actions.tree(body));
        return crud.create("rail_participant", RailParticipant.class, body, idempotencyKey, Crud.params());
    }

    @Override
    public ResponseEntity<RailParticipant> railParticipantsUpdate(UUID id, String ifMatch, RailParticipantUpdate body) {
        TenantContext.require();
        participants.validate(actions.tree(body));
        return crud.update("rail_participant", RailParticipant.class, body, ifMatch, Crud.params().with("id", id));
    }

    @Override
    public ResponseEntity<Map<String, Object>> railParticipantsLookup(String idempotencyKey,
                                                                    RailParticipantsLookupRequest body) {
        return actions.object(idempotencyKey, null, "lookup", body, () -> participants.lookup(actions.tree(body)));
    }
}
