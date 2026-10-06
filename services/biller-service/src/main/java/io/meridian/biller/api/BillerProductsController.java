package io.meridian.biller.api;

import io.meridian.biller.api.generated.BillerProductsApi;
import io.meridian.biller.api.generated.model.BillerProduct;
import io.meridian.biller.api.generated.model.BillerProductCreate;
import io.meridian.biller.api.generated.model.BillerProductUpdate;
import io.meridian.biller.application.CatalogService;
import io.meridian.biller.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Fixed products of a biller (airtime denominations, bundles, packages). */
@RestController
public class BillerProductsController implements BillerProductsApi {
    private static final Set<String> PROTECTED = Set.of("biller_id", "product_code", "currency");

    private final CatalogService catalog;
    private final CrudEngine crud;
    private final Actions actions;
    private final Rows rows;

    public BillerProductsController(CatalogService catalog, CrudEngine crud, Actions actions, Rows rows) {
        this.catalog = catalog;
        this.crud = crud;
        this.actions = actions;
        this.rows = rows;
    }

    @Override
    public ResponseEntity<BillerProduct> billerProductsCreate(String idempotencyKey, BillerProductCreate body) {
        TenantContext.require();
        JsonNode p = actions.tree(body);
        CatalogService.validateProduct(p, catalog.biller(Rows.uuid(p, "biller_id")));
        if (rows.scalar("select count(*) from biller_product where biller_id = ? and product_code = ?", Long.class,
                Rows.uuid(p, "biller_id"), Rows.str(p, "product_code")).orElse(0L) > 0) {
            throw ApiException.conflict("product_exists", "The biller already has a product with this code");
        }
        return crud.create("biller_product", BillerProduct.class, body, idempotencyKey, Crud.params());
    }

    @Override
    public ResponseEntity<BillerProduct> billerProductsUpdate(UUID id, String ifMatch, BillerProductUpdate body) {
        TenantContext.require();
        ObjectNode merged = (ObjectNode) actions.tree(crud.get("biller_product", BillerProduct.class,
                Crud.params().with("id", id)).getBody());
        JsonNode req = actions.tree(body);
        req.properties().forEach(e -> {
            if (!e.getValue().isNull()) {
                merged.set(e.getKey(), e.getValue());
            }
        });
        for (String f : PROTECTED) {
            if (req.hasNonNull(f) && !req.get(f).equals(merged.get(f))) {
                throw ApiException.unprocessable("field_not_updatable", f + " cannot be changed with PATCH");
            }
        }
        CatalogService.validateProduct(merged, catalog.biller(Rows.uuid(merged, "biller_id")));
        return crud.update("biller_product", BillerProduct.class, body, ifMatch, Crud.params().with("id", id));
    }
}
