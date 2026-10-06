package io.meridian.openbanking.api;

import io.meridian.openbanking.api.generated.ObFundsConfirmationsApi;
import io.meridian.openbanking.api.generated.model.ObFundsConfirmation;
import io.meridian.openbanking.api.generated.model.ObFundsConfirmationCreate;
import io.meridian.openbanking.application.FundsConfirmationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** CBPII confirmation of funds (yes/no from the ledger available balance; access-logged). */
@RestController
public class ObFundsConfirmationsController implements ObFundsConfirmationsApi {
    private final FundsConfirmationService service;
    private final Actions actions;
    private final TppCalls calls;

    public ObFundsConfirmationsController(FundsConfirmationService service, Actions actions, TppCalls calls) {
        this.service = service;
        this.actions = actions;
        this.calls = calls;
    }

    @Override
    public ResponseEntity<ObFundsConfirmation> obFundsConfirmationsCreate(String idempotencyKey, ObFundsConfirmationCreate body) {
        JsonNode req = actions.tree(body);
        return calls.logged(ObConsentsController.uuid(req, "tpp_id"), ObConsentsController.uuid(req, "ob_consent_id"),
                () -> actions.create(idempotencyKey, body, ObFundsConfirmation.class, () -> service.confirm(body)));
    }
}
