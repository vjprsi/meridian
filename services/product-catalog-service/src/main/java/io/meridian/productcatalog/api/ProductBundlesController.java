package io.meridian.productcatalog.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.productcatalog.api.generated.ProductBundlesApi;
import io.meridian.productcatalog.api.generated.model.ProductBundleItemPage;
import io.meridian.productcatalog.infrastructure.CatalogStore;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Product bundles (generic CRUD) and their items. */
@RestController
public class ProductBundlesController implements ProductBundlesApi {
    private final CatalogStore store;
    private final CrudEngine crud;

    public ProductBundlesController(CatalogStore store, CrudEngine crud) {
        this.store = store;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<ProductBundleItemPage> productBundlesItems(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        store.require("product_bundle", id);
        return crud.list("product_bundle_item", ProductBundleItemPage.class,
                Crud.params().with("product_bundle_id", id).with("limit", limit).with("cursor", cursor));
    }
}
