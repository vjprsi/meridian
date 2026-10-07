package io.meridian.merchantchannel.api;

import io.meridian.merchantchannel.api.generated.MerchantAppSettlementsApi;
import io.meridian.merchantchannel.api.generated.model.MerchantSettlement;
import io.meridian.merchantchannel.api.generated.model.MerchantSettlementPage;
import io.meridian.merchantchannel.application.CollectionService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Settlements of the merchant and their reports. */
@RestController
public class MerchantAppSettlementsController implements MerchantAppSettlementsApi {
    private final CollectionService collections;
    private final Http http;

    public MerchantAppSettlementsController(CollectionService collections, Http http) {
        this.collections = collections;
        this.http = http;
    }

    @Override
    public ResponseEntity<MerchantSettlementPage> merchantAppSettlementsList(String status, Integer limit, String cursor) {
        return http.ok(collections.settlements(status, limit, cursor), MerchantSettlementPage.class);
    }

    @Override
    public ResponseEntity<MerchantSettlement> merchantAppSettlementsGet(UUID settlementId) {
        return http.ok(collections.settlement(settlementId), MerchantSettlement.class);
    }

    @Override
    public ResponseEntity<String> merchantAppSettlementsReport(UUID settlementId) {
        return Http.file(collections.report(settlementId), "text/csv", "settlement-" + settlementId + ".csv");
    }
}
