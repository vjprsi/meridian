package io.meridian.payment.api;

import io.meridian.payment.api.generated.PayeeVerificationsApi;
import io.meridian.payment.api.generated.model.PayeeVerification;
import io.meridian.payment.api.generated.model.PayeeVerificationCreate;
import io.meridian.payment.application.PayeeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Stand-alone Confirmation of Payee / name enquiry; results are computed, never client-supplied. */
@RestController
public class PayeeVerificationsController implements PayeeVerificationsApi {
    private final Actions actions;
    private final PayeeService payees;

    public PayeeVerificationsController(Actions actions, PayeeService payees) {
        this.actions = actions;
        this.payees = payees;
    }

    @Override
    public ResponseEntity<PayeeVerification> payeeVerificationsCreate(String idempotencyKey, PayeeVerificationCreate body) {
        return actions.create(idempotencyKey, body, PayeeVerification.class, () -> payees.verifyStandalone(actions.tree(body)));
    }
}
