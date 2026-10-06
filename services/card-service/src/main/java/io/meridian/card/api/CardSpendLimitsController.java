package io.meridian.card.api;

import io.meridian.card.api.generated.CardSpendLimitsApi;
import io.meridian.card.api.generated.model.CardSpendLimit;
import io.meridian.card.api.generated.model.CardSpendLimitCreate;
import io.meridian.card.api.generated.model.CardSpendLimitUpdate;
import io.meridian.card.application.ControlService;
import io.meridian.common.tenancy.TenantContext;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Spend limits (card or program scope); usage is counted by card-authorization-service. */
@RestController
public class CardSpendLimitsController implements CardSpendLimitsApi {
    private final ControlService controls;
    private final Actions actions;

    public CardSpendLimitsController(ControlService controls, Actions actions) {
        this.controls = controls;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<CardSpendLimit> cardSpendLimitsCreate(String idempotencyKey, CardSpendLimitCreate body) {
        return actions.create(idempotencyKey, body, CardSpendLimit.class, () -> controls.createLimit(actions.tree(body)));
    }

    @Override
    public ResponseEntity<CardSpendLimit> cardSpendLimitsUpdate(UUID id, String ifMatch, CardSpendLimitUpdate body) {
        TenantContext.require();
        return controls.update("card_spend_limit", id, ifMatch, body, actions.tree(body), CardSpendLimit.class);
    }

    @Override
    public ResponseEntity<Void> cardSpendLimitsDelete(UUID id) {
        TenantContext.require();
        controls.deleteLimit(id);
        return ResponseEntity.noContent().build();
    }
}
