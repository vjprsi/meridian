package io.meridian.merchantchannel.api;

import io.meridian.merchantchannel.api.generated.MerchantAppStoresApi;
import io.meridian.merchantchannel.api.generated.model.MerchantStore;
import io.meridian.merchantchannel.api.generated.model.MerchantStorePage;
import io.meridian.merchantchannel.application.StoreService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Stores of the merchant the user may work in. */
@RestController
public class MerchantAppStoresController implements MerchantAppStoresApi {
    private final StoreService stores;
    private final Http http;

    public MerchantAppStoresController(StoreService stores, Http http) {
        this.stores = stores;
        this.http = http;
    }

    @Override
    public ResponseEntity<MerchantStorePage> merchantAppStoresList(Integer limit, String cursor) {
        return http.ok(stores.stores(), MerchantStorePage.class);
    }

    @Override
    public ResponseEntity<MerchantStore> merchantAppStoresGet(UUID storeId) {
        return http.ok(stores.store(storeId), MerchantStore.class);
    }
}
