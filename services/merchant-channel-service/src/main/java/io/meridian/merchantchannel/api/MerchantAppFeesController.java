package io.meridian.merchantchannel.api;

import io.meridian.merchantchannel.api.generated.MerchantAppFeesApi;
import io.meridian.merchantchannel.application.CollectionService;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Fee (MDR) statement of the merchant. */
@RestController
public class MerchantAppFeesController implements MerchantAppFeesApi {
    private final CollectionService collections;
    private final Http http;

    public MerchantAppFeesController(CollectionService collections, Http http) {
        this.collections = collections;
        this.http = http;
    }

    @Override
    public ResponseEntity<Map<String, Object>> merchantAppFeesGet(LocalDate from, LocalDate to) {
        return http.object(collections.fees(from, to));
    }

    @Override
    public ResponseEntity<String> merchantAppFeesStatement(LocalDate from, LocalDate to) {
        return Http.file(collections.feeStatement(from, to), "text/csv",
                "fee-statement-" + (from == null ? "start" : from) + "-" + (to == null ? "today" : to) + ".csv");
    }
}
