package io.meridian.businesschannel.api;

import io.meridian.businesschannel.api.generated.BusinessPaymentLinksApi;
import io.meridian.businesschannel.api.generated.model.BusinessPaymentLinksCreateRequest;
import io.meridian.businesschannel.api.generated.model.BusinessPaymentLinksDisableRequest;
import io.meridian.businesschannel.api.generated.model.PaymentLink;
import io.meridian.businesschannel.api.generated.model.PaymentLinkPage;
import io.meridian.businesschannel.api.generated.model.PaymentPage;
import io.meridian.businesschannel.application.CollectionService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Payment links collecting into the business's accounts. */
@RestController
public class BusinessPaymentLinksController implements BusinessPaymentLinksApi {
    private final CollectionService collections;
    private final Http http;

    public BusinessPaymentLinksController(CollectionService collections, Http http) {
        this.collections = collections;
        this.http = http;
    }

    @Override
    public ResponseEntity<PaymentLinkPage> businessPaymentLinksList(Integer limit, String cursor) {
        return http.ok(collections.paymentLinks(limit, cursor), PaymentLinkPage.class);
    }

    @Override
    public ResponseEntity<java.util.Map<String, Object>> businessPaymentLinksCreate(String idempotencyKey,
                                                                  BusinessPaymentLinksCreateRequest body) {
        return http.outcome(idempotencyKey, body, () -> collections.createPaymentLink(http.body(body), idempotencyKey));
    }

    @Override
    public ResponseEntity<PaymentLink> businessPaymentLinksGet(UUID linkId) {
        return http.ok(collections.paymentLink(linkId), PaymentLink.class);
    }

    @Override
    public ResponseEntity<PaymentLink> businessPaymentLinksDisable(UUID linkId, String idempotencyKey,
                                                                   BusinessPaymentLinksDisableRequest body) {
        return http.ok(collections.disablePaymentLink(linkId, http.body(body), idempotencyKey), PaymentLink.class);
    }

    @Override
    public ResponseEntity<PaymentPage> businessPaymentLinksPayments(UUID linkId, Integer limit, String cursor) {
        return http.ok(collections.paymentLinkPayments(linkId, limit, cursor), PaymentPage.class);
    }
}
