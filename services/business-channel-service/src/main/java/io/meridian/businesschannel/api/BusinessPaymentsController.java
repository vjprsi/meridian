package io.meridian.businesschannel.api;

import io.meridian.businesschannel.api.generated.BusinessPaymentsApi;
import io.meridian.businesschannel.api.generated.model.BusinessPayment;
import io.meridian.businesschannel.api.generated.model.BusinessPaymentApprovalPage;
import io.meridian.businesschannel.api.generated.model.BusinessPaymentPage;
import io.meridian.businesschannel.api.generated.model.BusinessPaymentsApproveRequest;
import io.meridian.businesschannel.api.generated.model.BusinessPaymentsCancelRequest;
import io.meridian.businesschannel.api.generated.model.BusinessPaymentsCreateRequest;
import io.meridian.businesschannel.api.generated.model.BusinessPaymentsRejectRequest;
import io.meridian.businesschannel.application.PaymentService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Payments with multi-level approvals. */
@RestController
public class BusinessPaymentsController implements BusinessPaymentsApi {
    private final PaymentService payments;
    private final Http http;

    public BusinessPaymentsController(PaymentService payments, Http http) {
        this.payments = payments;
        this.http = http;
    }

    @Override
    public ResponseEntity<BusinessPaymentPage> businessPaymentsList(String status, String kind, UUID accountId,
                                                                    Integer limit, String cursor) {
        return http.ok(payments.list(status, kind, accountId, limit, cursor), BusinessPaymentPage.class);
    }

    @Override
    public ResponseEntity<BusinessPayment> businessPaymentsCreate(String idempotencyKey, BusinessPaymentsCreateRequest body) {
        return http.once(idempotencyKey, body, BusinessPayment.class, () -> payments.create(http.body(body)));
    }

    @Override
    public ResponseEntity<BusinessPayment> businessPaymentsGet(UUID paymentId) {
        return http.ok(payments.get(paymentId), BusinessPayment.class);
    }

    @Override
    public ResponseEntity<BusinessPayment> businessPaymentsSubmit(UUID paymentId, String idempotencyKey) {
        return http.once(idempotencyKey, null, BusinessPayment.class, () -> payments.submit(paymentId));
    }

    @Override
    public ResponseEntity<BusinessPayment> businessPaymentsApprove(UUID paymentId, String idempotencyKey,
                                                                   BusinessPaymentsApproveRequest body) {
        return http.once(idempotencyKey, body, BusinessPayment.class, () -> payments.approve(paymentId, http.body(body)));
    }

    @Override
    public ResponseEntity<BusinessPayment> businessPaymentsReject(UUID paymentId, String idempotencyKey,
                                                                  BusinessPaymentsRejectRequest body) {
        return http.once(idempotencyKey, body, BusinessPayment.class, () -> payments.reject(paymentId, http.body(body)));
    }

    @Override
    public ResponseEntity<BusinessPayment> businessPaymentsCancel(UUID paymentId, String idempotencyKey,
                                                                  BusinessPaymentsCancelRequest body) {
        return http.once(idempotencyKey, body, BusinessPayment.class, () -> payments.cancel(paymentId, http.body(body)));
    }

    @Override
    public ResponseEntity<BusinessPayment> businessPaymentsRelease(UUID paymentId, String idempotencyKey) {
        return http.ok(payments.retryRelease(paymentId), BusinessPayment.class);
    }

    @Override
    public ResponseEntity<BusinessPaymentApprovalPage> businessPaymentsApprovals(UUID paymentId, Integer limit,
                                                                                 String cursor) {
        return http.ok(payments.approvals(paymentId), BusinessPaymentApprovalPage.class);
    }

    @Override
    public ResponseEntity<Map<String, Object>> businessPaymentsDownstream(UUID paymentId) {
        return http.object(payments.downstream(paymentId));
    }
}
