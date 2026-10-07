package io.meridian.merchantchannel.api;

import io.meridian.merchantchannel.api.generated.MerchantAppRefundsApi;
import io.meridian.merchantchannel.api.generated.model.MerchantAppRefundsApproveRequest;
import io.meridian.merchantchannel.api.generated.model.MerchantAppRefundsCreateRequest;
import io.meridian.merchantchannel.api.generated.model.MerchantAppRefundsRejectRequest;
import io.meridian.merchantchannel.api.generated.model.MerchantRefundRequest;
import io.meridian.merchantchannel.api.generated.model.MerchantRefundRequestPage;
import io.meridian.merchantchannel.application.RefundService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Refund requests with four-eyes approval. */
@RestController
public class MerchantAppRefundsController implements MerchantAppRefundsApi {
    private final RefundService refunds;
    private final Http http;

    public MerchantAppRefundsController(RefundService refunds, Http http) {
        this.refunds = refunds;
        this.http = http;
    }

    @Override
    public ResponseEntity<MerchantRefundRequestPage> merchantAppRefundsList(String status, UUID storeId, Integer limit,
                                                                            String cursor) {
        return http.ok(refunds.list(status, storeId, limit, cursor), MerchantRefundRequestPage.class);
    }

    @Override
    public ResponseEntity<MerchantRefundRequest> merchantAppRefundsCreate(String idempotencyKey,
                                                                          MerchantAppRefundsCreateRequest body) {
        return http.once(idempotencyKey, body, MerchantRefundRequest.class, () -> refunds.create(http.body(body)));
    }

    @Override
    public ResponseEntity<MerchantRefundRequest> merchantAppRefundsGet(UUID refundId) {
        return http.ok(refunds.get(refundId), MerchantRefundRequest.class);
    }

    @Override
    public ResponseEntity<MerchantRefundRequest> merchantAppRefundsApprove(UUID refundId, String idempotencyKey,
                                                                           MerchantAppRefundsApproveRequest body) {
        return http.once(idempotencyKey, body, MerchantRefundRequest.class,
                () -> refunds.approve(refundId, http.body(body)));
    }

    @Override
    public ResponseEntity<MerchantRefundRequest> merchantAppRefundsReject(UUID refundId, String idempotencyKey,
                                                                          MerchantAppRefundsRejectRequest body) {
        return http.once(idempotencyKey, body, MerchantRefundRequest.class,
                () -> refunds.reject(refundId, http.body(body)));
    }

    @Override
    public ResponseEntity<MerchantRefundRequest> merchantAppRefundsCancel(UUID refundId, String idempotencyKey) {
        return http.once(idempotencyKey, null, MerchantRefundRequest.class, () -> refunds.cancel(refundId));
    }
}
