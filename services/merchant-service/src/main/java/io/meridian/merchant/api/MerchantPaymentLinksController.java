package io.meridian.merchant.api;

import io.meridian.merchant.api.generated.MerchantPaymentLinksApi;
import io.meridian.merchant.api.generated.model.MerchantPaymentLink;
import io.meridian.merchant.api.generated.model.MerchantPaymentLinkCreate;
import io.meridian.merchant.api.generated.model.MerchantPaymentLinkUpdate;
import io.meridian.merchant.api.generated.model.MerchantPaymentLinksPayRequest;
import io.meridian.merchant.api.generated.model.WalletTransaction;
import io.meridian.merchant.application.AcceptanceService;
import io.meridian.web.crud.CrudEngine;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Shareable payment links. */
@RestController
public class MerchantPaymentLinksController implements MerchantPaymentLinksApi {
    private final AcceptanceService acceptance;
    private final Actions actions;
    private final CrudEngine crud;

    public MerchantPaymentLinksController(AcceptanceService acceptance, Actions actions, CrudEngine crud) {
        this.acceptance = acceptance;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<MerchantPaymentLink> merchantPaymentLinksCreate(String idempotencyKey, MerchantPaymentLinkCreate body) {
        return actions.create(idempotencyKey, body, MerchantPaymentLink.class, () -> acceptance.createLink(actions.tree(body)));
    }

    @Override
    public ResponseEntity<MerchantPaymentLink> merchantPaymentLinksUpdate(UUID id, String ifMatch, MerchantPaymentLinkUpdate body) {
        return actions.guardedUpdate(crud, "merchant_payment_link", MerchantPaymentLink.class, id, ifMatch, body,
                Set.of("merchant_id", "amount", "currency", "max_uses"), "create a new link instead");
    }

    @Override
    public ResponseEntity<MerchantPaymentLink> merchantPaymentLinksDeactivate(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "deactivate", null, MerchantPaymentLink.class,
                () -> acceptance.deactivateLink(id));
    }

    @Override
    public ResponseEntity<WalletTransaction> merchantPaymentLinksPay(UUID id, String idempotencyKey,
                                                                    MerchantPaymentLinksPayRequest body) {
        return actions.action(idempotencyKey, id, "pay", body, WalletTransaction.class,
                () -> acceptance.payLink(id, actions.tree(body), idempotencyKey));
    }
}
