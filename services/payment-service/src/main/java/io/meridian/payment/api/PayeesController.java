package io.meridian.payment.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.payment.api.generated.PayeesApi;
import io.meridian.payment.api.generated.model.Payee;
import io.meridian.payment.api.generated.model.PayeeCreate;
import io.meridian.payment.api.generated.model.PayeeUpdate;
import io.meridian.payment.api.generated.model.PayeeVerification;
import io.meridian.payment.api.generated.model.PayeesBlockRequest;
import io.meridian.payment.api.generated.model.PayeesUnblockRequest;
import io.meridian.payment.application.PayeeService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Payee book: validated beneficiaries, Confirmation of Payee, trust and blocking, soft delete. */
@RestController
public class PayeesController implements PayeesApi {
    private final Actions actions;
    private final PayeeService payees;

    public PayeesController(Actions actions, PayeeService payees) {
        this.actions = actions;
        this.payees = payees;
    }

    @Override
    public ResponseEntity<Payee> payeesCreate(String idempotencyKey, PayeeCreate body) {
        return actions.create(idempotencyKey, body, Payee.class, () -> payees.create(actions.tree(body)));
    }

    @Override
    public ResponseEntity<Payee> payeesUpdate(UUID id, String ifMatch, PayeeUpdate body) {
        TenantContext.require();
        int version = Actions.version(ifMatch);
        var row = payees.update(id, version, actions.tree(body));
        return ResponseEntity.ok().eTag("\"" + row.path("row_version").asInt() + "\"").body(actions.model(row, Payee.class));
    }

    @Override
    public ResponseEntity<Void> payeesDelete(UUID id) {
        TenantContext.require();
        payees.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<PayeeVerification> payeesVerify(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "verify", null, PayeeVerification.class, () -> payees.verify(id));
    }

    @Override
    public ResponseEntity<Payee> payeesTrust(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "trust", null, Payee.class, () -> payees.trust(id));
    }

    @Override
    public ResponseEntity<Payee> payeesBlock(UUID id, String idempotencyKey, PayeesBlockRequest body) {
        String reason = Actions.text(actions.tree(body), "reason");
        return actions.action(idempotencyKey, id, "block", body, Payee.class, () -> payees.block(id, reason));
    }

    @Override
    public ResponseEntity<Payee> payeesUnblock(UUID id, String idempotencyKey, PayeesUnblockRequest body) {
        return actions.action(idempotencyKey, id, "unblock", body, Payee.class, () -> payees.unblock(id));
    }
}
