package io.meridian.merchantchannel.api;

import io.meridian.merchantchannel.api.generated.MerchantAppPayoutAccountApi;
import io.meridian.merchantchannel.api.generated.model.DepositAccountPage;
import io.meridian.merchantchannel.application.PayoutService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The merchant's payout (settlement) bank account. */
@RestController
public class MerchantAppPayoutAccountController implements MerchantAppPayoutAccountApi {
    private final PayoutService payouts;
    private final Http http;

    public MerchantAppPayoutAccountController(PayoutService payouts, Http http) {
        this.payouts = payouts;
        this.http = http;
    }

    @Override
    public ResponseEntity<Map<String, Object>> merchantAppPayoutAccountGet() {
        return http.object(payouts.account());
    }

    @Override
    public ResponseEntity<DepositAccountPage> merchantAppPayoutAccountEligibleAccounts(Integer limit, String cursor) {
        return http.ok(payouts.eligibleAccounts(), DepositAccountPage.class);
    }
}
