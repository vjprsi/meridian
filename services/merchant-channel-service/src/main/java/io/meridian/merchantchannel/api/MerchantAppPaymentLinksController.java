package io.meridian.merchantchannel.api;

import io.meridian.merchantchannel.api.generated.MerchantAppPaymentLinksApi;
import io.meridian.merchantchannel.api.generated.model.MerchantAppPaymentLinksCreateRequest;
import io.meridian.merchantchannel.api.generated.model.MerchantPaymentLink;
import io.meridian.merchantchannel.api.generated.model.MerchantPaymentLinkPage;
import io.meridian.merchantchannel.application.CollectionService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Payment links of the merchant. */
@RestController
public class MerchantAppPaymentLinksController implements MerchantAppPaymentLinksApi {
    private final CollectionService collections;
    private final Http http;

    public MerchantAppPaymentLinksController(CollectionService collections, Http http) {
        this.collections = collections;
        this.http = http;
    }

    @Override
    public ResponseEntity<MerchantPaymentLinkPage> merchantAppPaymentLinksList(Boolean isActive, Integer limit,
                                                                               String cursor) {
        return http.ok(collections.links(isActive, limit, cursor), MerchantPaymentLinkPage.class);
    }

    @Override
    public ResponseEntity<MerchantPaymentLink> merchantAppPaymentLinksCreate(String idempotencyKey,
            MerchantAppPaymentLinksCreateRequest body) {
        return http.ok(collections.createLink(http.body(body), idempotencyKey), MerchantPaymentLink.class);
    }

    @Override
    public ResponseEntity<MerchantPaymentLink> merchantAppPaymentLinksGet(UUID linkId) {
        return http.ok(collections.link(linkId), MerchantPaymentLink.class);
    }

    @Override
    public ResponseEntity<MerchantPaymentLink> merchantAppPaymentLinksCancel(UUID linkId, String idempotencyKey) {
        return http.ok(collections.cancelLink(linkId, idempotencyKey), MerchantPaymentLink.class);
    }
}
