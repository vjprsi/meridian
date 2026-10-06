package io.meridian.openbanking.api;

import io.meridian.openbanking.api.generated.ObAuthorisationsApi;
import io.meridian.openbanking.api.generated.model.ObAuthorisation;
import io.meridian.openbanking.api.generated.model.ObAuthorisationsApproveRequest;
import io.meridian.openbanking.api.generated.model.ObAuthorisationsDenyRequest;
import io.meridian.openbanking.api.generated.model.ObAuthorisationsSelectAccountsRequest;
import io.meridian.openbanking.application.ConsentService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** SCA authorisation sessions: account selection, approval and denial by the customer. */
@RestController
public class ObAuthorisationsController implements ObAuthorisationsApi {
    private final ConsentService service;
    private final Actions actions;

    public ObAuthorisationsController(ConsentService service, Actions actions) {
        this.service = service;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<ObAuthorisation> obAuthorisationsSelectAccounts(UUID id, String idempotencyKey,
                                                                         ObAuthorisationsSelectAccountsRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "select-accounts", body, ObAuthorisation.class,
                () -> service.selectAccounts(id, ObConsentsController.uuid(req, "customer_id"), req.get("account_ids")));
    }

    @Override
    public ResponseEntity<ObAuthorisation> obAuthorisationsApprove(UUID id, String idempotencyKey,
                                                                  ObAuthorisationsApproveRequest body) {
        return actions.action(idempotencyKey, id, "approve", body, ObAuthorisation.class,
                () -> service.approve(id, Http.text(actions.tree(body), "authentication_method")));
    }

    @Override
    public ResponseEntity<ObAuthorisation> obAuthorisationsDeny(UUID id, String idempotencyKey,
                                                               ObAuthorisationsDenyRequest body) {
        return actions.action(idempotencyKey, id, "deny", body, ObAuthorisation.class,
                () -> service.deny(id, Http.text(actions.tree(body), "reason")));
    }
}
