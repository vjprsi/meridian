package io.meridian.merchantchannel.api;

import io.meridian.merchantchannel.api.generated.MerchantAppMeApi;
import io.meridian.merchantchannel.application.StoreService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in merchant user, the merchant and the stores the user may work in. */
@RestController
public class MerchantAppMeController implements MerchantAppMeApi {
    private final StoreService stores;
    private final Http http;

    public MerchantAppMeController(StoreService stores, Http http) {
        this.stores = stores;
        this.http = http;
    }

    @Override
    public ResponseEntity<Map<String, Object>> merchantAppMeGet() {
        return http.object(stores.me());
    }
}
