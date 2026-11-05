package io.meridian.customerchannel.api;

import io.meridian.customerchannel.api.generated.MyMerchantPaymentsApi;
import io.meridian.customerchannel.api.generated.model.MyMerchantPaymentsCreateRequest;
import io.meridian.customerchannel.api.generated.model.WalletTransaction;
import io.meridian.customerchannel.application.QrService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Pay a merchant / till from one of the customer's wallets (step-up). */
@RestController
public class MyMerchantPaymentsController implements MyMerchantPaymentsApi {
    private final QrService qr;
    private final Http http;

    public MyMerchantPaymentsController(QrService qr, Http http) {
        this.qr = qr;
        this.http = http;
    }

    @Override
    public ResponseEntity<WalletTransaction> myMerchantPaymentsCreate(String idempotencyKey,
                                                                      MyMerchantPaymentsCreateRequest body) {
        return http.ok(qr.merchantPayment(http.body(body), idempotencyKey), WalletTransaction.class);
    }
}
