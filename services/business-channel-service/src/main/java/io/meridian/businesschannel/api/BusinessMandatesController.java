package io.meridian.businesschannel.api;

import io.meridian.businesschannel.api.generated.BusinessMandatesApi;
import io.meridian.businesschannel.api.generated.model.BusinessMandatesAddCollectionsRequest;
import io.meridian.businesschannel.api.generated.model.BusinessMandatesCancelRequest;
import io.meridian.businesschannel.api.generated.model.BusinessMandatesCreateRequest;
import io.meridian.businesschannel.api.generated.model.DirectDebitCollection;
import io.meridian.businesschannel.api.generated.model.DirectDebitCollectionPage;
import io.meridian.businesschannel.api.generated.model.PaymentMandate;
import io.meridian.businesschannel.api.generated.model.PaymentMandatePage;
import io.meridian.businesschannel.application.CollectionService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Direct-debit mandates and collections of the business. */
@RestController
public class BusinessMandatesController implements BusinessMandatesApi {
    private final CollectionService collections;
    private final Http http;

    public BusinessMandatesController(CollectionService collections, Http http) {
        this.collections = collections;
        this.http = http;
    }

    @Override
    public ResponseEntity<PaymentMandatePage> businessMandatesList(Integer limit, String cursor) {
        return http.ok(collections.mandates(limit, cursor), PaymentMandatePage.class);
    }

    @Override
    public ResponseEntity<PaymentMandate> businessMandatesCreate(String idempotencyKey, BusinessMandatesCreateRequest body) {
        return http.ok(collections.createMandate(http.body(body), idempotencyKey), PaymentMandate.class);
    }

    @Override
    public ResponseEntity<PaymentMandate> businessMandatesGet(UUID mandateId) {
        return http.ok(collections.mandate(mandateId), PaymentMandate.class);
    }

    @Override
    public ResponseEntity<PaymentMandate> businessMandatesCancel(UUID mandateId, String idempotencyKey,
                                                                 BusinessMandatesCancelRequest body) {
        return http.ok(collections.cancelMandate(mandateId, http.body(body), idempotencyKey), PaymentMandate.class);
    }

    @Override
    public ResponseEntity<DirectDebitCollectionPage> businessMandatesCollections(UUID mandateId, Integer limit,
                                                                                 String cursor) {
        return http.ok(collections.collections(mandateId, limit, cursor), DirectDebitCollectionPage.class);
    }

    @Override
    public ResponseEntity<DirectDebitCollection> businessMandatesAddCollections(UUID mandateId, String idempotencyKey,
            BusinessMandatesAddCollectionsRequest body) {
        return http.ok(collections.addCollection(mandateId, http.body(body), idempotencyKey), DirectDebitCollection.class);
    }
}
