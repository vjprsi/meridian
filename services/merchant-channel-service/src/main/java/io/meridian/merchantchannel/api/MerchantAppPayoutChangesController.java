package io.meridian.merchantchannel.api;

import io.meridian.merchantchannel.api.generated.MerchantAppPayoutChangesApi;
import io.meridian.merchantchannel.api.generated.model.MerchantAppPayoutChangesApproveRequest;
import io.meridian.merchantchannel.api.generated.model.MerchantAppPayoutChangesCreateRequest;
import io.meridian.merchantchannel.api.generated.model.MerchantAppPayoutChangesRejectRequest;
import io.meridian.merchantchannel.api.generated.model.MerchantChangeRequest;
import io.meridian.merchantchannel.api.generated.model.MerchantChangeRequestPage;
import io.meridian.merchantchannel.application.PayoutService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Payout account changes with four-eyes approval. */
@RestController
public class MerchantAppPayoutChangesController implements MerchantAppPayoutChangesApi {
    private final PayoutService payouts;
    private final Http http;

    public MerchantAppPayoutChangesController(PayoutService payouts, Http http) {
        this.payouts = payouts;
        this.http = http;
    }

    @Override
    public ResponseEntity<MerchantChangeRequestPage> merchantAppPayoutChangesList(String status, Integer limit,
                                                                                  String cursor) {
        return http.ok(payouts.list(status, limit, cursor), MerchantChangeRequestPage.class);
    }

    @Override
    public ResponseEntity<MerchantChangeRequest> merchantAppPayoutChangesCreate(String idempotencyKey,
            MerchantAppPayoutChangesCreateRequest body) {
        return http.once(idempotencyKey, body, MerchantChangeRequest.class, () -> payouts.request(http.body(body)));
    }

    @Override
    public ResponseEntity<MerchantChangeRequest> merchantAppPayoutChangesGet(UUID changeId) {
        return http.ok(payouts.get(changeId), MerchantChangeRequest.class);
    }

    @Override
    public ResponseEntity<MerchantChangeRequest> merchantAppPayoutChangesApprove(UUID changeId, String idempotencyKey,
            MerchantAppPayoutChangesApproveRequest body) {
        return http.once(idempotencyKey, body, MerchantChangeRequest.class,
                () -> payouts.approve(changeId, http.body(body)));
    }

    @Override
    public ResponseEntity<MerchantChangeRequest> merchantAppPayoutChangesReject(UUID changeId, String idempotencyKey,
            MerchantAppPayoutChangesRejectRequest body) {
        return http.once(idempotencyKey, body, MerchantChangeRequest.class,
                () -> payouts.reject(changeId, http.body(body)));
    }

    @Override
    public ResponseEntity<MerchantChangeRequest> merchantAppPayoutChangesCancel(UUID changeId, String idempotencyKey) {
        return http.once(idempotencyKey, null, MerchantChangeRequest.class, () -> payouts.cancel(changeId));
    }
}
