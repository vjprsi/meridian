package io.meridian.businesschannel.api;

import io.meridian.businesschannel.api.generated.BusinessApprovalQueueApi;
import io.meridian.businesschannel.api.generated.model.BusinessPaymentPage;
import io.meridian.businesschannel.application.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Payments waiting for the signed-in user's approval. */
@RestController
public class BusinessApprovalQueueController implements BusinessApprovalQueueApi {
    private final PaymentService payments;
    private final Http http;

    public BusinessApprovalQueueController(PaymentService payments, Http http) {
        this.payments = payments;
        this.http = http;
    }

    @Override
    public ResponseEntity<BusinessPaymentPage> businessApprovalQueueList(Integer limit, String cursor) {
        return http.ok(payments.queue(limit), BusinessPaymentPage.class);
    }
}
