package io.meridian.payment.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.payment.api.generated.PaymentMandatesApi;
import io.meridian.payment.api.generated.model.DirectDebitCollectionPage;
import io.meridian.payment.api.generated.model.PaymentMandate;
import io.meridian.payment.api.generated.model.PaymentMandateCreate;
import io.meridian.payment.api.generated.model.PaymentMandateUpdate;
import io.meridian.payment.api.generated.model.PaymentMandatesCancelRequest;
import io.meridian.payment.api.generated.model.PaymentMandatesSuspendRequest;
import io.meridian.payment.application.MandateService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Direct-debit mandates: validated creation, activation after signature, suspend / resume / cancel. */
@RestController
public class PaymentMandatesController implements PaymentMandatesApi {
    private static final Set<String> PROTECTED = Set.of("scheme", "mandate_reference", "direction", "customer_id",
            "debtor_account_id", "creditor_account_id", "currency", "creditor_id");

    private final Actions actions;
    private final MandateService mandates;
    private final CrudEngine crud;

    public PaymentMandatesController(Actions actions, MandateService mandates, CrudEngine crud) {
        this.actions = actions;
        this.mandates = mandates;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<PaymentMandate> paymentMandatesCreate(String idempotencyKey, PaymentMandateCreate body) {
        return actions.create(idempotencyKey, body, PaymentMandate.class, () -> mandates.create(actions.tree(body)));
    }

    @Override
    public ResponseEntity<PaymentMandate> paymentMandatesUpdate(UUID id, String ifMatch, PaymentMandateUpdate body) {
        TenantContext.require();
        JsonNode req = actions.tree(body);
        for (String f : PROTECTED) {
            if (req.hasNonNull(f)) {
                throw ApiException.unprocessable("field_not_updatable",
                        f + " identifies the mandate and cannot be changed; cancel it and create a new mandate");
            }
        }
        return crud.update("payment_mandate", PaymentMandate.class, body, ifMatch, Crud.params().with("id", id));
    }

    @Override
    public ResponseEntity<PaymentMandate> paymentMandatesActivate(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "activate", null, PaymentMandate.class, () -> mandates.activate(id));
    }

    @Override
    public ResponseEntity<PaymentMandate> paymentMandatesSuspend(UUID id, String idempotencyKey, PaymentMandatesSuspendRequest body) {
        String reason = Actions.text(actions.tree(body), "reason");
        return actions.action(idempotencyKey, id, "suspend", body, PaymentMandate.class, () -> mandates.suspend(id, reason));
    }

    @Override
    public ResponseEntity<PaymentMandate> paymentMandatesResume(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "resume", null, PaymentMandate.class, () -> mandates.resume(id));
    }

    @Override
    public ResponseEntity<PaymentMandate> paymentMandatesCancel(UUID id, String idempotencyKey, PaymentMandatesCancelRequest body) {
        String reason = Actions.text(actions.tree(body), "reason");
        return actions.action(idempotencyKey, id, "cancel", body, PaymentMandate.class, () -> mandates.cancel(id, reason));
    }

    @Override
    public ResponseEntity<DirectDebitCollectionPage> paymentMandatesCollections(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        crud.get("payment_mandate", PaymentMandate.class, Crud.params().with("id", id));
        return actions.page("direct_debit_collection", "mandate_id", id, limit, cursor, DirectDebitCollectionPage.class);
    }
}
