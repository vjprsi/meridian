package io.meridian.insights.api;

import io.meridian.insights.api.generated.SpendCategoriesApi;
import io.meridian.insights.api.generated.model.SpendCategory;
import io.meridian.insights.api.generated.model.SpendCategoryCreate;
import io.meridian.insights.api.generated.model.SpendCategoryUpdate;
import io.meridian.insights.application.Json;
import io.meridian.insights.application.TaxonomyService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Category taxonomy (global): level / parent and MCC validation. */
@RestController
public class SpendCategoriesController implements SpendCategoriesApi {
    private final TaxonomyService taxonomy;
    private final Http http;

    public SpendCategoriesController(TaxonomyService taxonomy, Http http) {
        this.taxonomy = taxonomy;
        this.http = http;
    }

    @Override
    public ResponseEntity<SpendCategory> spendCategoriesCreate(String idempotencyKey, SpendCategoryCreate body) {
        return http.created(idempotencyKey, "category.create", body, SpendCategory.class, () -> taxonomy.createCategory(body));
    }

    @Override
    public ResponseEntity<SpendCategory> spendCategoriesUpdate(UUID id, String ifMatch, SpendCategoryUpdate body) {
        int version = Json.parseVersion(ifMatch);
        return http.withEtag(taxonomy.updateCategory(id, body, version), SpendCategory.class);
    }
}
