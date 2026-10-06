package io.meridian.interop.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.interop.api.generated.InteropParticipantsApi;
import io.meridian.interop.api.generated.model.InteropParticipant;
import io.meridian.interop.api.generated.model.InteropParticipantCreate;
import io.meridian.interop.api.generated.model.InteropParticipantUpdate;
import io.meridian.interop.application.SwitchService;
import io.meridian.interop.infrastructure.Rows;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Participants reachable through a switch (maintained manually or by /sync-participants). */
@RestController
public class InteropParticipantsController implements InteropParticipantsApi {
    private final CrudEngine crud;
    private final Actions actions;
    private final Rows rows;

    public InteropParticipantsController(CrudEngine crud, Actions actions, Rows rows) {
        this.crud = crud;
        this.actions = actions;
        this.rows = rows;
    }

    @Override
    public ResponseEntity<InteropParticipant> interopParticipantsCreate(String idempotencyKey, InteropParticipantCreate body) {
        TenantContext.require();
        JsonNode req = actions.tree(body);
        SwitchService.validateParticipant(req);
        if (rows.find("interop_switch", Rows.uuid(req, "interop_switch_id")).isEmpty()) {
            throw ApiException.unprocessable("switch_not_found", "The switch does not exist");
        }
        if (rows.scalar("select count(*) from interop_participant where interop_switch_id = ? and fsp_id = ?", Long.class,
                Rows.uuid(req, "interop_switch_id"), Rows.str(req, "fsp_id")).orElse(0L) > 0) {
            throw ApiException.conflict("participant_exists", "The participant is already registered on this switch");
        }
        return crud.create("interop_participant", InteropParticipant.class, body, idempotencyKey, Crud.params());
    }

    @Override
    public ResponseEntity<InteropParticipant> interopParticipantsUpdate(UUID id, String ifMatch, InteropParticipantUpdate body) {
        SwitchService.validateParticipant(actions.tree(body));
        return actions.guardedUpdate(crud, "interop_participant", InteropParticipant.class, id, ifMatch, body,
                Set.of("interop_switch_id", "fsp_id"), "register a new participant instead");
    }
}
