package io.meridian.businesschannel.api;

import io.meridian.businesschannel.api.generated.BusinessBulkPaymentsApi;
import io.meridian.businesschannel.api.generated.model.BusinessBulkPaymentsCreateRequest;
import io.meridian.businesschannel.api.generated.model.BusinessPayment;
import io.meridian.businesschannel.application.PaymentService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Bulk payments and payroll uploaded as CSV. */
@RestController
public class BusinessBulkPaymentsController implements BusinessBulkPaymentsApi {
    private final PaymentService payments;
    private final Http http;

    public BusinessBulkPaymentsController(PaymentService payments, Http http) {
        this.payments = payments;
        this.http = http;
    }

    @Override
    public ResponseEntity<BusinessPayment> businessBulkPaymentsCreate(String idempotencyKey,
                                                                      BusinessBulkPaymentsCreateRequest body) {
        return http.once(idempotencyKey, body, BusinessPayment.class, () -> payments.upload(http.body(body)));
    }

    @Override
    public ResponseEntity<String> businessBulkPaymentsReport(UUID paymentId) {
        return Http.file(payments.bulkReport(paymentId), "text/csv; charset=UTF-8", "bulk-report-" + paymentId + ".csv");
    }
}
