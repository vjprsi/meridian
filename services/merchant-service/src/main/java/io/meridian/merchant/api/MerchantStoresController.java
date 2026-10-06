package io.meridian.merchant.api;

import io.meridian.merchant.api.generated.MerchantStoresApi;
import io.meridian.merchant.api.generated.model.MerchantStore;
import io.meridian.merchant.api.generated.model.MerchantStoreCreate;
import io.meridian.merchant.api.generated.model.MerchantStoreUpdate;
import io.meridian.merchant.application.MerchantService;
import io.meridian.web.crud.CrudEngine;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Merchant stores. */
@RestController
public class MerchantStoresController implements MerchantStoresApi {
    private final MerchantService merchants;
    private final Actions actions;
    private final CrudEngine crud;

    public MerchantStoresController(MerchantService merchants, Actions actions, CrudEngine crud) {
        this.merchants = merchants;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<MerchantStore> merchantStoresCreate(String idempotencyKey, MerchantStoreCreate body) {
        return actions.create(idempotencyKey, body, MerchantStore.class, () -> merchants.createStore(actions.tree(body)));
    }

    @Override
    public ResponseEntity<MerchantStore> merchantStoresUpdate(UUID id, String ifMatch, MerchantStoreUpdate body) {
        return actions.guardedUpdate(crud, "merchant_store", MerchantStore.class, id, ifMatch, body,
                Set.of("merchant_id", "store_code"), "a store cannot move to another merchant");
    }
}
