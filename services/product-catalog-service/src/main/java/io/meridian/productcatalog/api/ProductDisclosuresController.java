package io.meridian.productcatalog.api;

import io.meridian.productcatalog.api.generated.ProductDisclosuresApi;
import io.meridian.productcatalog.api.generated.model.ProductDisclosure;
import io.meridian.productcatalog.api.generated.model.ProductDisclosureCreate;
import io.meridian.productcatalog.api.generated.model.ProductDisclosureUpdate;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customer-facing documents of a product version. Writes are only allowed while the owning version is DRAFT (409 version_not_editable otherwise) and are
 * validated against product rules (422 invalid_configuration).
 */
@RestController
public class ProductDisclosuresController implements ProductDisclosuresApi {
    private final ContentEndpoints content;

    public ProductDisclosuresController(ContentEndpoints content) {
        this.content = content;
    }

    @Override
    public ResponseEntity<ProductDisclosure> productDisclosuresCreate(String idempotencyKey, ProductDisclosureCreate body) {
        return content.create("product_disclosure", ProductDisclosure.class, idempotencyKey, body);
    }

    @Override
    public ResponseEntity<ProductDisclosure> productDisclosuresUpdate(UUID id, String ifMatch, ProductDisclosureUpdate body) {
        return content.update("product_disclosure", ProductDisclosure.class, id, ifMatch, body);
    }
}
