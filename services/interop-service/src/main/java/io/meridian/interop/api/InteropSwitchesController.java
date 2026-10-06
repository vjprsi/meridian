package io.meridian.interop.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.interop.api.generated.InteropSwitchesApi;
import io.meridian.interop.api.generated.model.InteropParticipantPage;
import io.meridian.interop.api.generated.model.InteropSwitch;
import io.meridian.interop.api.generated.model.InteropSwitchCreate;
import io.meridian.interop.api.generated.model.InteropSwitchUpdate;
import io.meridian.interop.application.SwitchService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Connected switches: configuration, participant directory sync and the net position. */
@RestController
public class InteropSwitchesController implements InteropSwitchesApi {
    private final SwitchService switches;
    private final CrudEngine crud;
    private final Actions actions;

    public InteropSwitchesController(SwitchService switches, CrudEngine crud, Actions actions) {
        this.switches = switches;
        this.crud = crud;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<InteropSwitch> interopSwitchesCreate(String idempotencyKey, InteropSwitchCreate body) {
        TenantContext.require();
        SwitchService.validateSwitch(actions.tree(body));
        return crud.create("interop_switch", InteropSwitch.class, body, idempotencyKey, Crud.params());
    }

    @Override
    public ResponseEntity<InteropSwitch> interopSwitchesUpdate(UUID id, String ifMatch, InteropSwitchUpdate body) {
        TenantContext.require();
        crud.get("interop_switch", InteropSwitch.class, Crud.params().with("id", id));
        switches.guardUpdate(id, actions.tree(body));
        return actions.guardedUpdate(crud, "interop_switch", InteropSwitch.class, id, ifMatch, body,
                Set.of("code", "participant_id"), "register a new switch instead");
    }

    @Override
    public ResponseEntity<InteropParticipantPage> interopSwitchesParticipants(UUID id, Integer limit, String cursor) {
        crud.get("interop_switch", InteropSwitch.class, Crud.params().with("id", id));
        return crud.list("interop_participant", InteropParticipantPage.class,
                Crud.params().with("interop_switch_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<Map<String, Object>> interopSwitchesSyncParticipants(UUID id, String idempotencyKey) {
        return actions.objectAction(idempotencyKey, "sync-participants:" + id, null, () -> switches.syncParticipants(id));
    }

    @Override
    public ResponseEntity<Map<String, Object>> interopSwitchesPosition(UUID id) {
        TenantContext.require();
        return actions.object(switches.position(id));
    }
}
