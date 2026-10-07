package io.meridian.customerchannel.api;

import io.meridian.customerchannel.api.generated.MyPayeesApi;
import io.meridian.customerchannel.api.generated.model.MyPayeesCreateRequest;
import io.meridian.customerchannel.api.generated.model.Payee;
import io.meridian.customerchannel.api.generated.model.PayeePage;
import io.meridian.customerchannel.api.generated.model.PayeeVerification;
import io.meridian.customerchannel.application.PaymentService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The customer's saved beneficiaries. */
@RestController
public class MyPayeesController implements MyPayeesApi {
    private final PaymentService payments;
    private final Http http;

    public MyPayeesController(PaymentService payments, Http http) {
        this.payments = payments;
        this.http = http;
    }

    @Override
    public ResponseEntity<PayeePage> myPayeesList(Integer limit, String cursor) {
        return http.ok(payments.payees(limit, cursor), PayeePage.class);
    }

    @Override
    public ResponseEntity<Payee> myPayeesCreate(String idempotencyKey, MyPayeesCreateRequest body) {
        return http.ok(payments.addPayee(http.body(body), idempotencyKey), Payee.class);
    }

    @Override
    public ResponseEntity<Void> myPayeesDelete(UUID payeeId, String idempotencyKey) {
        payments.removePayee(payeeId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<PayeeVerification> myPayeesVerify(UUID payeeId, String idempotencyKey) {
        return http.ok(payments.verifyPayee(payeeId, idempotencyKey), PayeeVerification.class);
    }

    @Override
    public ResponseEntity<Payee> myPayeesTrust(UUID payeeId, String idempotencyKey) {
        return http.ok(payments.trustPayee(payeeId, idempotencyKey), Payee.class);
    }
}
