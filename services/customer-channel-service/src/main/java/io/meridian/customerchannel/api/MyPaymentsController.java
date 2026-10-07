package io.meridian.customerchannel.api;

import io.meridian.customerchannel.api.generated.MyPaymentsApi;
import io.meridian.customerchannel.api.generated.model.MyPaymentsCreateRequest;
import io.meridian.customerchannel.api.generated.model.MyPaymentsValidateRequest;
import io.meridian.customerchannel.api.generated.model.Payment;
import io.meridian.customerchannel.api.generated.model.PaymentPage;
import io.meridian.customerchannel.application.PaymentService;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Payments of the customer: validate, make (step-up), list, get and receipt. */
@RestController
public class MyPaymentsController implements MyPaymentsApi {
    private final PaymentService payments;
    private final Http http;

    public MyPaymentsController(PaymentService payments, Http http) {
        this.payments = payments;
        this.http = http;
    }

    @Override
    public ResponseEntity<PaymentPage> myPaymentsList(UUID accountId, String status, Integer limit, String cursor) {
        return http.ok(payments.payments(accountId, status, limit, cursor), PaymentPage.class);
    }

    @Override
    public ResponseEntity<Map<String, Object>> myPaymentsValidate(String idempotencyKey,
                                                                  MyPaymentsValidateRequest body) {
        return http.object(payments.validate(http.body(body), idempotencyKey));
    }

    @Override
    public ResponseEntity<Payment> myPaymentsCreate(String idempotencyKey, MyPaymentsCreateRequest body) {
        return http.ok(payments.create(http.body(body), idempotencyKey), Payment.class);
    }

    @Override
    public ResponseEntity<Payment> myPaymentsGet(UUID paymentId) {
        return http.ok(payments.payment(paymentId), Payment.class);
    }

    @Override
    public ResponseEntity<Resource> myPaymentsReceipt(UUID paymentId) {
        return Http.binary(payments.receipt(paymentId), "receipt-" + paymentId + ".pdf");
    }
}
