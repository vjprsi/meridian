package io.meridian.payment.api;

import io.meridian.payment.api.generated.RequestsToPayApi;
import io.meridian.payment.api.generated.model.Payment;
import io.meridian.payment.api.generated.model.RequestToPay;
import io.meridian.payment.api.generated.model.RequestToPayCreate;
import io.meridian.payment.api.generated.model.RequestsToPayAcceptRequest;
import io.meridian.payment.api.generated.model.RequestsToPayCancelRequest;
import io.meridian.payment.api.generated.model.RequestsToPayDeclineRequest;
import io.meridian.payment.application.ReceivablesService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Request-to-pay: send, payer accept (pays with a book transfer), decline, requester cancel. */
@RestController
public class RequestsToPayController implements RequestsToPayApi {
    private final Actions actions;
    private final ReceivablesService receivables;

    public RequestsToPayController(Actions actions, ReceivablesService receivables) {
        this.actions = actions;
        this.receivables = receivables;
    }

    @Override
    public ResponseEntity<RequestToPay> requestsToPayCreate(String idempotencyKey, RequestToPayCreate body) {
        return actions.create(idempotencyKey, body, RequestToPay.class, () -> receivables.createRequest(actions.tree(body)));
    }

    @Override
    public ResponseEntity<Payment> requestsToPayAccept(UUID id, String idempotencyKey, RequestsToPayAcceptRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "accept", body, Payment.class,
                () -> receivables.acceptRequest(id, Actions.uuid(req, "debtor_account_id"), Actions.decimal(req, "amount"),
                        idempotencyKey));
    }

    @Override
    public ResponseEntity<RequestToPay> requestsToPayDecline(UUID id, String idempotencyKey, RequestsToPayDeclineRequest body) {
        String reason = Actions.text(actions.tree(body), "reason");
        return actions.action(idempotencyKey, id, "decline", body, RequestToPay.class,
                () -> receivables.declineRequest(id, reason, false));
    }

    @Override
    public ResponseEntity<RequestToPay> requestsToPayCancel(UUID id, String idempotencyKey, RequestsToPayCancelRequest body) {
        String reason = Actions.text(actions.tree(body), "reason");
        return actions.action(idempotencyKey, id, "cancel", body, RequestToPay.class,
                () -> receivables.declineRequest(id, reason, true));
    }
}
