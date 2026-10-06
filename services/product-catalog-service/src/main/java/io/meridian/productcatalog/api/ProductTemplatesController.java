package io.meridian.productcatalog.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.productcatalog.api.generated.ProductTemplatesApi;
import io.meridian.productcatalog.api.generated.model.ProductTemplate;
import io.meridian.productcatalog.api.generated.model.ProductTemplateCreate;
import io.meridian.productcatalog.api.generated.model.ProductTemplateUpdate;
import io.meridian.productcatalog.infrastructure.CatalogStore;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Product templates: system templates are shipped by the platform and read-only to tenants. */
@RestController
public class ProductTemplatesController implements ProductTemplatesApi {
    private final CatalogStore store;
    private final CrudEngine crud;
    private final JsonMapper json;

    public ProductTemplatesController(CatalogStore store, CrudEngine crud, JsonMapper json) {
        this.store = store;
        this.crud = crud;
        this.json = json;
    }

    @Override
    public ResponseEntity<ProductTemplate> productTemplatesCreate(String idempotencyKey, ProductTemplateCreate body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        if (req.path("is_system").asBoolean(false)) {
            throw ApiException.unprocessable("system_template_reserved", "Only the platform can ship system templates");
        }
        return crud.create("product_template", ProductTemplate.class, body, idempotencyKey, Crud.params());
    }

    @Override
    public ResponseEntity<ProductTemplate> productTemplatesUpdate(UUID id, String ifMatch, ProductTemplateUpdate body) {
        TenantContext.require();
        JsonNode current = store.require("product_template", id);
        JsonNode req = json.valueToTree(body);
        if (current.get("is_system").asBoolean() || req.path("is_system").asBoolean(false)) {
            throw ApiException.conflict("system_template_readonly", "System templates are read-only");
        }
        return crud.update("product_template", ProductTemplate.class, body, ifMatch, Crud.params().with("id", id));
    }
}
