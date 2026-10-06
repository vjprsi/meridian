package io.meridian.search.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.search.api.generated.SavedSearchesApi;
import io.meridian.search.api.generated.model.SavedSearch;
import io.meridian.search.api.generated.model.SavedSearchCreate;
import io.meridian.search.api.generated.model.SavedSearchUpdate;
import io.meridian.search.api.generated.model.SavedSearchesRunRequest;
import io.meridian.search.application.SearchService;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Saved searches: validated definitions and execution (with the caller's masking rules). */
@RestController
public class SavedSearchesController implements SavedSearchesApi {
    private static final String TABLE = "saved_search";

    private final SearchService search;
    private final Permissions permissions;
    private final CrudEngine crud;
    private final Updates updates;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public SavedSearchesController(SearchService search, Permissions permissions, CrudEngine crud, Updates updates,
                                   Idempotency idempotency, JsonMapper json) {
        this.search = search;
        this.permissions = permissions;
        this.crud = crud;
        this.updates = updates;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<SavedSearch> savedSearchesCreate(String idempotencyKey, SavedSearchCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, SavedSearch.class, () -> {
            ObjectNode values = crud.toColumns(TABLE, body);
            search.validateSaved(values);
            return ResponseEntity.status(HttpStatus.CREATED).body(crud.read(crud.insert(TABLE, values), SavedSearch.class));
        });
    }

    @Override
    public ResponseEntity<SavedSearch> savedSearchesUpdate(UUID id, String ifMatch, SavedSearchUpdate body) {
        TenantContext.require();
        return updates.apply(TABLE, id, ifMatch, crud.toColumns(TABLE, body), SavedSearch.class,
                (merged, changes) -> search.validateSaved(merged));
    }

    /** Read-only execution: like the query endpoint, results are not replayed from the idempotency store. */
    @Override
    public ResponseEntity<Void> savedSearchesRun(UUID id, String idempotencyKey, SavedSearchesRunRequest body) {
        TenantContext.require();
        JsonNode req = body == null ? json.createObjectNode() : json.valueToTree(body);
        return Http.ok(search.runSaved(id, req.hasNonNull("limit") ? req.get("limit").asInt() : null,
                Http.text(req, "cursor"), permissions.piiAllowed()));
    }
}
