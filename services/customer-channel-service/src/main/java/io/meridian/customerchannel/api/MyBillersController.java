package io.meridian.customerchannel.api;

import io.meridian.customerchannel.api.generated.MyBillersApi;
import io.meridian.customerchannel.api.generated.model.BillerPage;
import io.meridian.customerchannel.api.generated.model.MyBillersValidateReferenceRequest;
import io.meridian.customerchannel.application.BillerService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Billers and operators available to the customer: list, one biller with products, and reference validation. */
@RestController
public class MyBillersController implements MyBillersApi {
    private final BillerService billers;
    private final Http http;

    public MyBillersController(BillerService billers, Http http) {
        this.billers = billers;
        this.http = http;
    }

    @Override
    public ResponseEntity<BillerPage> myBillersList(String category, String country, String q, Integer limit,
                                                    String cursor) {
        return http.ok(billers.billers(category, country, q, limit, cursor), BillerPage.class);
    }

    @Override
    public ResponseEntity<Map<String, Object>> myBillersGet(UUID billerId) {
        return http.object(billers.biller(billerId));
    }

    @Override
    public ResponseEntity<Map<String, Object>> myBillersValidateReference(UUID billerId, String idempotencyKey,
                                                                          MyBillersValidateReferenceRequest body) {
        return http.object(billers.validateReference(billerId, http.body(body), idempotencyKey));
    }
}
