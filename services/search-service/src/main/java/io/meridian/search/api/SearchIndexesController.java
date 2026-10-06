package io.meridian.search.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.search.api.generated.SearchIndexesApi;
import io.meridian.search.api.generated.model.SearchIndex;
import io.meridian.search.api.generated.model.SearchIndexCreate;
import io.meridian.search.api.generated.model.SearchIndexJob;
import io.meridian.search.api.generated.model.SearchIndexUpdate;
import io.meridian.search.api.generated.model.SearchIndexesCatchUpRequest;
import io.meridian.search.application.IndexJobs;
import io.meridian.search.application.IndexService;
import io.meridian.search.infrastructure.Rows;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Index definitions, zero-downtime reindex, event catch-up and health statistics. */
@RestController
public class SearchIndexesController implements SearchIndexesApi {
    private static final String TABLE = "search_index";

    private final IndexService indexes;
    private final IndexJobs jobs;
    private final Rows rows;
    private final CrudEngine crud;
    private final Updates updates;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public SearchIndexesController(IndexService indexes, IndexJobs jobs, Rows rows, CrudEngine crud, Updates updates,
                                   Idempotency idempotency, JsonMapper json) {
        this.indexes = indexes;
        this.jobs = jobs;
        this.rows = rows;
        this.crud = crud;
        this.updates = updates;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<SearchIndex> searchIndexesCreate(String idempotencyKey, SearchIndexCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, SearchIndex.class, () -> {
            ObjectNode values = indexes.prepareCreate(crud.toColumns(TABLE, body));
            return ResponseEntity.status(HttpStatus.CREATED).body(crud.read(crud.insert(TABLE, values), SearchIndex.class));
        });
    }

    @Override
    public ResponseEntity<SearchIndex> searchIndexesUpdate(UUID id, String ifMatch, SearchIndexUpdate body) {
        TenantContext.require();
        JsonNode current = rows.require(TABLE, id, false);
        return updates.apply(TABLE, id, ifMatch, crud.toColumns(TABLE, body), SearchIndex.class,
                (merged, changes) -> indexes.prepareUpdate(current, merged, changes));
    }

    @Override
    public ResponseEntity<SearchIndexJob> searchIndexesReindex(UUID id, String idempotencyKey) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, json.createObjectNode().put("reindex", id.toString()), SearchIndexJob.class,
                () -> ResponseEntity.ok(crud.read(jobs.reindex(id), SearchIndexJob.class)));
    }

    @Override
    public ResponseEntity<SearchIndexJob> searchIndexesCatchUp(UUID id, String idempotencyKey, SearchIndexesCatchUpRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        OffsetDateTime from = OffsetDateTime.parse(Http.required(req, "from_timestamp"));
        return idempotency.execute(idempotencyKey, req, SearchIndexJob.class,
                () -> ResponseEntity.ok(crud.read(jobs.catchUp(id, from), SearchIndexJob.class)));
    }

    @Override
    public ResponseEntity<SearchIndex> searchIndexesStats(UUID id) {
        TenantContext.require();
        return ResponseEntity.ok(crud.read(indexes.stats(id), SearchIndex.class));
    }
}
