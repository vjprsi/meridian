package io.meridian.customerchannel.api;

import io.meridian.customerchannel.api.generated.MyRewardsApi;
import io.meridian.customerchannel.api.generated.model.CashbackTransactionPage;
import io.meridian.customerchannel.api.generated.model.PointsAccountPage;
import io.meridian.customerchannel.api.generated.model.PointsLedgerEntryPage;
import io.meridian.customerchannel.application.EngagementService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The customer's points and cashback. */
@RestController
public class MyRewardsController implements MyRewardsApi {
    private final EngagementService engagement;
    private final Http http;

    public MyRewardsController(EngagementService engagement, Http http) {
        this.engagement = engagement;
        this.http = http;
    }

    @Override
    public ResponseEntity<PointsAccountPage> myRewardsPointsAccounts(Integer limit, String cursor) {
        return http.ok(engagement.pointsAccounts(limit, cursor), PointsAccountPage.class);
    }

    @Override
    public ResponseEntity<PointsLedgerEntryPage> myRewardsPointsAccountsEntries(UUID pointsAccountId, Integer limit,
                                                                                String cursor) {
        return http.ok(engagement.pointsEntries(pointsAccountId, limit, cursor), PointsLedgerEntryPage.class);
    }

    @Override
    public ResponseEntity<CashbackTransactionPage> myRewardsCashback(Integer limit, String cursor) {
        return http.ok(engagement.cashback(limit, cursor), CashbackTransactionPage.class);
    }
}
