package io.meridian.payment.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.payment.api.generated.PaymentLinksApi;
import io.meridian.payment.api.generated.model.Payment;
import io.meridian.payment.api.generated.model.PaymentLink;
import io.meridian.payment.api.generated.model.PaymentLinkCreate;
import io.meridian.payment.api.generated.model.PaymentLinkUpdate;
import io.meridian.payment.api.generated.model.PaymentLinksDisableRequest;
import io.meridian.payment.api.generated.model.PaymentLinksPayRequest;
import io.meridian.payment.api.generated.model.PaymentPage;
import io.meridian.payment.application.ReceivablesService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Payment links: generated slug and URL, fixed or open amounts, reusable caps, pay, disable, received payments. */
@RestController
public class PaymentLinksController implements PaymentLinksApi {
    private static final Set<String> PROTECTED = Set.of("currency", "credit_account_id", "credit_wallet_id", "customer_id",
            "merchant_id", "amount");

    private final Actions actions;
    private final ReceivablesService receivables;
    private final CrudEngine crud;

    public PaymentLinksController(Actions actions, ReceivablesService receivables, CrudEngine crud) {
        this.actions = actions;
        this.receivables = receivables;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<PaymentLink> paymentLinksCreate(String idempotencyKey, PaymentLinkCreate body) {
        return actions.create(idempotencyKey, body, PaymentLink.class, () -> receivables.createLink(actions.tree(body)));
    }

    @Override
    public ResponseEntity<PaymentLink> paymentLinksUpdate(UUID id, String ifMatch, PaymentLinkUpdate body) {
        TenantContext.require();
        JsonNode req = actions.tree(body);
        for (String f : PROTECTED) {
            if (req.hasNonNull(f)) {
                throw ApiException.unprocessable("field_not_updatable", f + " cannot be changed; create a new link");
            }
        }
        PaymentLink current = crud.get("payment_link", PaymentLink.class, Crud.params().with("id", id)).getBody();
        ObjectNode merged = (ObjectNode) actions.tree(current);
        merged.setAll((ObjectNode) req);
        receivables.validateLinkAmounts(merged);
        return crud.update("payment_link", PaymentLink.class, body, ifMatch, Crud.params().with("id", id));
    }

    @Override
    public ResponseEntity<Payment> paymentLinksPay(UUID id, String idempotencyKey, PaymentLinksPayRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "pay", body, Payment.class,
                () -> receivables.payLink(id, Actions.uuid(req, "debtor_account_id"), Actions.decimal(req, "amount"),
                        Actions.text(req, "payer_name"), idempotencyKey));
    }

    @Override
    public ResponseEntity<PaymentLink> paymentLinksDisable(UUID id, String idempotencyKey, PaymentLinksDisableRequest body) {
        return actions.action(idempotencyKey, id, "disable", body, PaymentLink.class, () -> receivables.disableLink(id));
    }

    @Override
    public ResponseEntity<PaymentPage> paymentLinksPayments(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        return ResponseEntity.ok(actions.model(receivables.linkPayments(id, limit, cursor), PaymentPage.class));
    }
}
