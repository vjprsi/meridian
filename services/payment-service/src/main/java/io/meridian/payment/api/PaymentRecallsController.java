package io.meridian.payment.api;

import io.meridian.payment.api.generated.PaymentRecallsApi;
import io.meridian.payment.api.generated.model.PaymentRecall;
import io.meridian.payment.api.generated.model.PaymentRecallsAcceptRequest;
import io.meridian.payment.api.generated.model.PaymentRecallsInboundRequest;
import io.meridian.payment.api.generated.model.PaymentRecallsRejectRequest;
import io.meridian.payment.api.generated.model.PaymentRecallsResolveRequest;
import io.meridian.payment.application.ReturnService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Recalls: inbound camt.056 handling (accept / reject) and resolution of our outbound recalls (camt.029). */
@RestController
public class PaymentRecallsController implements PaymentRecallsApi {
    private final Actions actions;
    private final ReturnService returns;

    public PaymentRecallsController(Actions actions, ReturnService returns) {
        this.actions = actions;
        this.returns = returns;
    }

    @Override
    public ResponseEntity<PaymentRecall> paymentRecallsInbound(String idempotencyKey, PaymentRecallsInboundRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, null, "inbound-recall", body, PaymentRecall.class, () -> returns.inboundRecall(req));
    }

    @Override
    public ResponseEntity<PaymentRecall> paymentRecallsAccept(UUID id, String idempotencyKey, PaymentRecallsAcceptRequest body) {
        String comment = Actions.text(actions.tree(body), "comment");
        return actions.action(idempotencyKey, id, "accept", body, PaymentRecall.class, () -> returns.acceptInbound(id, comment));
    }

    @Override
    public ResponseEntity<PaymentRecall> paymentRecallsReject(UUID id, String idempotencyKey, PaymentRecallsRejectRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "reject", body, PaymentRecall.class,
                () -> returns.rejectInbound(id, Actions.text(req, "response_code"), Actions.text(req, "response_reason")));
    }

    @Override
    public ResponseEntity<PaymentRecall> paymentRecallsResolve(UUID id, String idempotencyKey, PaymentRecallsResolveRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "resolve", body, PaymentRecall.class,
                () -> returns.resolve(id, req.path("accepted").asBoolean(false), Actions.text(req, "response_code"),
                        Actions.text(req, "response_reason")));
    }
}
