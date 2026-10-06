package io.meridian.card.api;

import io.meridian.card.api.generated.CardControlsApi;
import io.meridian.card.api.generated.model.CardControl;
import io.meridian.card.api.generated.model.CardControlCreate;
import io.meridian.card.api.generated.model.CardControlUpdate;
import io.meridian.card.application.ControlService;
import io.meridian.common.tenancy.TenantContext;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Card controls (card or program scope) with type-specific validation and change events. */
@RestController
public class CardControlsController implements CardControlsApi {
    private final ControlService controls;
    private final Actions actions;

    public CardControlsController(ControlService controls, Actions actions) {
        this.controls = controls;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<CardControl> cardControlsCreate(String idempotencyKey, CardControlCreate body) {
        return actions.create(idempotencyKey, body, CardControl.class, () -> controls.createControl(actions.tree(body)));
    }

    @Override
    public ResponseEntity<CardControl> cardControlsUpdate(UUID id, String ifMatch, CardControlUpdate body) {
        TenantContext.require();
        return controls.update("card_control", id, ifMatch, body, actions.tree(body), CardControl.class);
    }

    @Override
    public ResponseEntity<Void> cardControlsDelete(UUID id) {
        TenantContext.require();
        controls.deleteControl(id);
        return ResponseEntity.noContent().build();
    }
}
