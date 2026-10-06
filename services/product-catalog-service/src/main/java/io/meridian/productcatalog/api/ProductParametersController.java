package io.meridian.productcatalog.api;

import io.meridian.productcatalog.api.generated.ProductParametersApi;
import io.meridian.productcatalog.api.generated.model.ProductParameter;
import io.meridian.productcatalog.api.generated.model.ProductParameterCreate;
import io.meridian.productcatalog.api.generated.model.ProductParameterUpdate;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Typed parameters of a product version. Writes are only allowed while the owning version is DRAFT (409 version_not_editable otherwise) and are
 * validated against product rules (422 invalid_configuration).
 */
@RestController
public class ProductParametersController implements ProductParametersApi {
    private final ContentEndpoints content;

    public ProductParametersController(ContentEndpoints content) {
        this.content = content;
    }

    @Override
    public ResponseEntity<ProductParameter> productParametersCreate(String idempotencyKey, ProductParameterCreate body) {
        return content.create("product_parameter", ProductParameter.class, idempotencyKey, body);
    }

    @Override
    public ResponseEntity<ProductParameter> productParametersUpdate(UUID id, String ifMatch, ProductParameterUpdate body) {
        return content.update("product_parameter", ProductParameter.class, id, ifMatch, body);
    }

    @Override
    public ResponseEntity<Void> productParametersDelete(UUID id) {
        return content.delete("product_parameter", id);
    }
}
