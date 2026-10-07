package io.meridian.fraud.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.fraud.api.generated.FraudListsApi;
import io.meridian.fraud.api.generated.model.FraudList;
import io.meridian.fraud.api.generated.model.FraudListCreate;
import io.meridian.fraud.api.generated.model.FraudListEntry;
import io.meridian.fraud.api.generated.model.FraudListEntryPage;
import io.meridian.fraud.api.generated.model.FraudListUpdate;
import io.meridian.fraud.api.generated.model.FraudListsAddEntriesRequest;
import io.meridian.fraud.api.generated.model.FraudListsLookupRequest;
import io.meridian.fraud.application.FraudListService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Block / allow / watch lists and their (hashed) entries. */
@RestController
public class FraudListsController implements FraudListsApi {
    private final FraudListService lists;
    private final CrudEngine crud;
    private final Http http;

    public FraudListsController(FraudListService lists, CrudEngine crud, Http http) {
        this.lists = lists;
        this.crud = crud;
        this.http = http;
    }

    @Override
    public ResponseEntity<FraudList> fraudListsCreate(String idempotencyKey, FraudListCreate body) {
        ResponseEntity<FraudList> r = crud.create("fraud_list", FraudList.class, body, idempotencyKey, Crud.params());
        lists.changed();
        return r;
    }

    @Override
    public ResponseEntity<FraudList> fraudListsUpdate(UUID id, String ifMatch, FraudListUpdate body) {
        ResponseEntity<FraudList> r = crud.update("fraud_list", FraudList.class, body, ifMatch, Crud.params().with("id", id));
        lists.changed();
        return r;
    }

    @Override
    public ResponseEntity<Void> fraudListsDelete(UUID id) {
        TenantContext.require();
        lists.deleteList(id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<FraudListEntryPage> fraudListsEntries(UUID id, Integer limit, String cursor) {
        crud.get("fraud_list", Object.class, Crud.params().with("id", id));
        return crud.list("fraud_list_entry", FraudListEntryPage.class,
                Crud.params().with("fraud_list_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<FraudListEntry> fraudListsAddEntries(UUID id, String idempotencyKey, FraudListsAddEntriesRequest body) {
        JsonNode req = http.tree(body);
        return http.once(idempotencyKey, body, FraudListEntry.class, () -> http.ok(lists.addEntry(id,
                Http.text(req, "value"), Http.text(req, "reason"), Http.text(req, "source"), Http.text(req, "expires_at")),
                FraudListEntry.class));
    }

    @Override
    public ResponseEntity<Map<String, Object>> fraudListsLookup(UUID id, String idempotencyKey, FraudListsLookupRequest body) {
        TenantContext.require();
        return ResponseEntity.ok(lists.lookup(id, Http.text(http.tree(body), "value")));
    }
}
