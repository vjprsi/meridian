package io.meridian.customerchannel.api;

import io.meridian.customerchannel.api.generated.MyAirtimeTopupsApi;
import io.meridian.customerchannel.api.generated.model.AirtimeTopup;
import io.meridian.customerchannel.api.generated.model.AirtimeTopupPage;
import io.meridian.customerchannel.api.generated.model.MyAirtimeTopupsCreateRequest;
import io.meridian.customerchannel.application.BillerService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The customer's airtime / data top-ups: list, create (step-up), get and receipt. */
@RestController
public class MyAirtimeTopupsController implements MyAirtimeTopupsApi {
    private final BillerService billers;
    private final Http http;

    public MyAirtimeTopupsController(BillerService billers, Http http) {
        this.billers = billers;
        this.http = http;
    }

    @Override
    public ResponseEntity<AirtimeTopupPage> myAirtimeTopupsList(Integer limit, String cursor) {
        return http.ok(billers.topups(limit, cursor), AirtimeTopupPage.class);
    }

    @Override
    public ResponseEntity<AirtimeTopup> myAirtimeTopupsCreate(String idempotencyKey,
                                                              MyAirtimeTopupsCreateRequest body) {
        return http.ok(billers.createTopup(http.body(body), idempotencyKey), AirtimeTopup.class);
    }

    @Override
    public ResponseEntity<AirtimeTopup> myAirtimeTopupsGet(UUID topupId) {
        return http.ok(billers.topup(topupId), AirtimeTopup.class);
    }

    @Override
    public ResponseEntity<Map<String, Object>> myAirtimeTopupsReceipt(UUID topupId) {
        return http.object(billers.topupReceipt(topupId));
    }
}
