package io.meridian.customerchannel.api;

import io.meridian.customerchannel.api.generated.MyBillPaymentsApi;
import io.meridian.customerchannel.api.generated.model.BillPayment;
import io.meridian.customerchannel.api.generated.model.BillPaymentPage;
import io.meridian.customerchannel.api.generated.model.MyBillPaymentsCreateRequest;
import io.meridian.customerchannel.api.generated.model.MyBillPaymentsInquireRequest;
import io.meridian.customerchannel.application.BillerService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The customer's bill payments: inquire, list, pay (step-up), get and receipt. */
@RestController
public class MyBillPaymentsController implements MyBillPaymentsApi {
    private final BillerService billers;
    private final Http http;

    public MyBillPaymentsController(BillerService billers, Http http) {
        this.billers = billers;
        this.http = http;
    }

    @Override
    public ResponseEntity<Map<String, Object>> myBillPaymentsInquire(String idempotencyKey,
                                                                     MyBillPaymentsInquireRequest body) {
        return http.object(billers.inquire(http.body(body), idempotencyKey));
    }

    @Override
    public ResponseEntity<BillPaymentPage> myBillPaymentsList(Integer limit, String cursor) {
        return http.ok(billers.billPayments(limit, cursor), BillPaymentPage.class);
    }

    @Override
    public ResponseEntity<BillPayment> myBillPaymentsCreate(String idempotencyKey,
                                                            MyBillPaymentsCreateRequest body) {
        return http.ok(billers.createBillPayment(http.body(body), idempotencyKey), BillPayment.class);
    }

    @Override
    public ResponseEntity<BillPayment> myBillPaymentsGet(UUID paymentId) {
        return http.ok(billers.billPayment(paymentId), BillPayment.class);
    }

    @Override
    public ResponseEntity<Map<String, Object>> myBillPaymentsReceipt(UUID paymentId) {
        return http.object(billers.billPaymentReceipt(paymentId));
    }
}
