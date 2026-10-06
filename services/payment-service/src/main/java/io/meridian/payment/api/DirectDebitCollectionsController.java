package io.meridian.payment.api;

import io.meridian.payment.api.generated.DirectDebitCollectionsApi;
import io.meridian.payment.api.generated.model.DirectDebitCollection;
import io.meridian.payment.api.generated.model.DirectDebitCollectionCreate;
import io.meridian.payment.api.generated.model.DirectDebitCollectionsCancelRequest;
import io.meridian.payment.api.generated.model.DirectDebitCollectionsRefundRequest;
import io.meridian.payment.api.generated.model.PaymentReturn;
import io.meridian.payment.application.MandateService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Direct-debit collections: scheduling under an active mandate, collection, cancellation and refunds. */
@RestController
public class DirectDebitCollectionsController implements DirectDebitCollectionsApi {
    private final Actions actions;
    private final MandateService mandates;

    public DirectDebitCollectionsController(Actions actions, MandateService mandates) {
        this.actions = actions;
        this.mandates = mandates;
    }

    @Override
    public ResponseEntity<DirectDebitCollection> directDebitCollectionsCreate(String idempotencyKey,
                                                                            DirectDebitCollectionCreate body) {
        return actions.create(idempotencyKey, body, DirectDebitCollection.class, () -> mandates.schedule(actions.tree(body)));
    }

    @Override
    public ResponseEntity<DirectDebitCollection> directDebitCollectionsCollect(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "collect", null, DirectDebitCollection.class, () -> mandates.collect(id));
    }

    @Override
    public ResponseEntity<DirectDebitCollection> directDebitCollectionsCancel(UUID id, String idempotencyKey,
                                                                            DirectDebitCollectionsCancelRequest body) {
        String reason = Actions.text(actions.tree(body), "reason");
        return actions.action(idempotencyKey, id, "cancel", body, DirectDebitCollection.class,
                () -> mandates.cancelCollection(id, reason));
    }

    @Override
    public ResponseEntity<PaymentReturn> directDebitCollectionsRefund(UUID id, String idempotencyKey,
                                                                    DirectDebitCollectionsRefundRequest body) {
        String code = Actions.text(actions.tree(body), "reason_code");
        return actions.action(idempotencyKey, id, "refund", body, PaymentReturn.class, () -> mandates.refund(id, code));
    }
}
