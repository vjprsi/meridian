package io.meridian.productcatalog.api;

import io.meridian.productcatalog.api.generated.ProductLimitTemplatesApi;
import io.meridian.productcatalog.api.generated.model.ProductLimitTemplate;
import io.meridian.productcatalog.api.generated.model.ProductLimitTemplateCreate;
import io.meridian.productcatalog.api.generated.model.ProductLimitTemplateUpdate;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Default limits of a product version. Writes are only allowed while the owning version is DRAFT (409 version_not_editable otherwise) and are
 * validated against product rules (422 invalid_configuration).
 */
@RestController
public class ProductLimitTemplatesController implements ProductLimitTemplatesApi {
    private final ContentEndpoints content;

    public ProductLimitTemplatesController(ContentEndpoints content) {
        this.content = content;
    }

    @Override
    public ResponseEntity<ProductLimitTemplate> productLimitTemplatesCreate(String idempotencyKey, ProductLimitTemplateCreate body) {
        return content.create("product_limit_template", ProductLimitTemplate.class, idempotencyKey, body);
    }

    @Override
    public ResponseEntity<ProductLimitTemplate> productLimitTemplatesUpdate(UUID id, String ifMatch, ProductLimitTemplateUpdate body) {
        return content.update("product_limit_template", ProductLimitTemplate.class, id, ifMatch, body);
    }

    @Override
    public ResponseEntity<Void> productLimitTemplatesDelete(UUID id) {
        return content.delete("product_limit_template", id);
    }
}
