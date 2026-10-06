package io.meridian.merchant.api;

import io.meridian.merchant.api.generated.MerchantTerminalsApi;
import io.meridian.merchant.api.generated.model.MerchantTerminal;
import io.meridian.merchant.api.generated.model.MerchantTerminalCreate;
import io.meridian.merchant.api.generated.model.MerchantTerminalUpdate;
import io.meridian.merchant.api.generated.model.MerchantTerminalsDeactivateRequest;
import io.meridian.merchant.application.MerchantService;
import io.meridian.web.crud.CrudEngine;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Merchant terminals. */
@RestController
public class MerchantTerminalsController implements MerchantTerminalsApi {
    private final MerchantService merchants;
    private final Actions actions;
    private final CrudEngine crud;

    public MerchantTerminalsController(MerchantService merchants, Actions actions, CrudEngine crud) {
        this.merchants = merchants;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<MerchantTerminal> merchantTerminalsCreate(String idempotencyKey, MerchantTerminalCreate body) {
        return actions.create(idempotencyKey, body, MerchantTerminal.class, () -> merchants.createTerminal(actions.tree(body)));
    }

    @Override
    public ResponseEntity<MerchantTerminal> merchantTerminalsUpdate(UUID id, String ifMatch, MerchantTerminalUpdate body) {
        return actions.guardedUpdate(crud, "merchant_terminal", MerchantTerminal.class, id, ifMatch, body,
                Set.of("merchant_id", "terminal_id", "merchant_store_id"), "use deactivate and register a new terminal");
    }

    @Override
    public ResponseEntity<MerchantTerminal> merchantTerminalsDeactivate(UUID id, String idempotencyKey,
                                                                       MerchantTerminalsDeactivateRequest body) {
        return actions.action(idempotencyKey, id, "deactivate", body, MerchantTerminal.class,
                () -> merchants.deactivateTerminal(id, Http.text(actions.tree(body), "reason")));
    }
}
