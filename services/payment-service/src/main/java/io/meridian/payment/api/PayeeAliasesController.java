package io.meridian.payment.api;

import io.meridian.payment.api.generated.PayeeAliasesApi;
import io.meridian.payment.api.generated.model.PayeeAlias;
import io.meridian.payment.api.generated.model.PayeeAliasCreate;
import io.meridian.payment.api.generated.model.PayeeAliasesPortRequest;
import io.meridian.payment.api.generated.model.PayeeAliasesResolveRequest;
import io.meridian.payment.api.generated.model.PayeeAliasesVerifyOwnershipRequest;
import io.meridian.payment.application.PayeeService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Proxy directory: register with OTP ownership proof, resolve to a masked payee, portability. */
@RestController
public class PayeeAliasesController implements PayeeAliasesApi {
    private final Actions actions;
    private final PayeeService payees;

    public PayeeAliasesController(Actions actions, PayeeService payees) {
        this.actions = actions;
        this.payees = payees;
    }

    @Override
    public ResponseEntity<PayeeAlias> payeeAliasesCreate(String idempotencyKey, PayeeAliasCreate body) {
        return actions.create(idempotencyKey, body, PayeeAlias.class, () -> payees.registerAlias(actions.tree(body)));
    }

    @Override
    public ResponseEntity<PayeeAlias> payeeAliasesResolve(String idempotencyKey, PayeeAliasesResolveRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, null, "resolve", body, PayeeAlias.class,
                () -> payees.resolve(Actions.text(req, "alias_type"), Actions.text(req, "alias_value")));
    }

    @Override
    public ResponseEntity<PayeeAlias> payeeAliasesVerifyOwnership(UUID id, String idempotencyKey,
                                                                 PayeeAliasesVerifyOwnershipRequest body) {
        String otp = Actions.text(actions.tree(body), "otp");
        return actions.action(idempotencyKey, id, "verify-ownership", body, PayeeAlias.class, () -> payees.verifyOwnership(id, otp));
    }

    @Override
    public ResponseEntity<PayeeAlias> payeeAliasesResendOtp(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "resend-otp", null, PayeeAlias.class, () -> payees.resendOtp(id));
    }

    @Override
    public ResponseEntity<PayeeAlias> payeeAliasesPort(UUID id, String idempotencyKey, PayeeAliasesPortRequest body) {
        String donor = Actions.text(actions.tree(body), "donor_institution");
        return actions.action(idempotencyKey, id, "port", body, PayeeAlias.class, () -> payees.requestPort(id, donor));
    }

    @Override
    public ResponseEntity<PayeeAlias> payeeAliasesCompletePort(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "complete-port", null, PayeeAlias.class, () -> payees.completePort(id));
    }
}
