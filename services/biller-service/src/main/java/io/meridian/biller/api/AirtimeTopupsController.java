package io.meridian.biller.api;

import io.meridian.biller.api.generated.AirtimeTopupsApi;
import io.meridian.biller.api.generated.model.AirtimeTopup;
import io.meridian.biller.api.generated.model.AirtimeTopupCreate;
import io.meridian.biller.application.PaymentService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Airtime and data top-ups paid from wallets. */
@RestController
public class AirtimeTopupsController implements AirtimeTopupsApi {
    private final PaymentService payments;
    private final Actions actions;

    public AirtimeTopupsController(PaymentService payments, Actions actions) {
        this.payments = payments;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<AirtimeTopup> airtimeTopupsCreate(String idempotencyKey, AirtimeTopupCreate body) {
        return actions.create(idempotencyKey, body, AirtimeTopup.class, () -> payments.topup(actions.tree(body), idempotencyKey));
    }

    @Override
    public ResponseEntity<AirtimeTopup> airtimeTopupsRetry(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "retry", null, AirtimeTopup.class,
                () -> payments.retry(PaymentService.Kind.TOPUP, id));
    }
}
