package io.meridian.payment.api;

import io.meridian.payment.api.generated.PaymentReturnsApi;
import io.meridian.payment.api.generated.model.PaymentReturn;
import io.meridian.payment.api.generated.model.PaymentReturnsInboundRequest;
import io.meridian.payment.application.ReturnService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Payment returns (pacs.004): list/get, and returns received for our outbound payments. */
@RestController
public class PaymentReturnsController implements PaymentReturnsApi {
    private final Actions actions;
    private final ReturnService returns;

    public PaymentReturnsController(Actions actions, ReturnService returns) {
        this.actions = actions;
        this.returns = returns;
    }

    @Override
    public ResponseEntity<PaymentReturn> paymentReturnsInbound(String idempotencyKey, PaymentReturnsInboundRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, null, "inbound-return", body, PaymentReturn.class, () -> returns.inboundReturn(req));
    }
}
