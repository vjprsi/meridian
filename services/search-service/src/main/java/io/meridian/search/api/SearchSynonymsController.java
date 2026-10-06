package io.meridian.search.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.search.api.generated.SearchSynonymsApi;
import io.meridian.search.api.generated.model.SearchSynonym;
import io.meridian.search.api.generated.model.SearchSynonymCreate;
import io.meridian.search.api.generated.model.SearchSynonymUpdate;
import io.meridian.search.application.SearchService;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Synonym sets. {@code terms} is stored normalised (lower case, distinct) as {@code {"terms": [...]}}, the
 * object form the generated contract can carry; the request may send that form or any object listing terms.
 */
@RestController
public class SearchSynonymsController implements SearchSynonymsApi {
    private static final String TABLE = "search_synonym";

    private final SearchService search;
    private final CrudEngine crud;
    private final Updates updates;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public SearchSynonymsController(SearchService search, CrudEngine crud, Updates updates, Idempotency idempotency,
                                    JsonMapper json) {
        this.search = search;
        this.crud = crud;
        this.updates = updates;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<SearchSynonym> searchSynonymsCreate(String idempotencyKey, SearchSynonymCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, SearchSynonym.class, () -> {
            ObjectNode values = crud.toColumns(TABLE, body);
            values.set("terms", json.createObjectNode().set("terms", search.validateSynonyms(values)));
            return ResponseEntity.status(HttpStatus.CREATED).body(crud.read(crud.insert(TABLE, values), SearchSynonym.class));
        });
    }

    @Override
    public ResponseEntity<SearchSynonym> searchSynonymsUpdate(UUID id, String ifMatch, SearchSynonymUpdate body) {
        TenantContext.require();
        return updates.apply(TABLE, id, ifMatch, crud.toColumns(TABLE, body), SearchSynonym.class, (merged, changes) -> {
            var terms = search.validateSynonyms(merged);
            if (changes.has("terms")) {
                changes.set("terms", json.createObjectNode().set("terms", terms));
            }
        });
    }
}
