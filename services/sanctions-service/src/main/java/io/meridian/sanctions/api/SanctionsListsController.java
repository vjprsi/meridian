package io.meridian.sanctions.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.sanctions.api.generated.SanctionsListsApi;
import io.meridian.sanctions.api.generated.model.SanctionsList;
import io.meridian.sanctions.api.generated.model.SanctionsListCreate;
import io.meridian.sanctions.api.generated.model.SanctionsListEntryPage;
import io.meridian.sanctions.api.generated.model.SanctionsListPage;
import io.meridian.sanctions.api.generated.model.SanctionsListUpdate;
import io.meridian.sanctions.api.generated.model.SanctionsListsImportRequest;
import io.meridian.sanctions.api.generated.model.SanctionsListsSyncRequest;
import io.meridian.sanctions.application.ListCatalog;
import io.meridian.sanctions.application.ListService;
import io.meridian.sanctions.infrastructure.Db;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Sanctions lists: global (platform-managed, read-only) and tenant-owned; sync and import. */
@RestController
public class SanctionsListsController implements SanctionsListsApi {
    private final ListService lists;
    private final ListCatalog catalog;
    private final Http http;

    public SanctionsListsController(ListService lists, ListCatalog catalog, Http http) {
        this.lists = lists;
        this.catalog = catalog;
        this.http = http;
    }

    @Override
    public ResponseEntity<SanctionsList> sanctionsListsCreate(String idempotencyKey, SanctionsListCreate body) {
        return http.once(idempotencyKey, body, SanctionsList.class,
                () -> http.respond(HttpStatus.CREATED, lists.createList(http.tree(body)), SanctionsList.class));
    }

    @Override
    public ResponseEntity<SanctionsList> sanctionsListsUpdate(UUID id, String ifMatch, SanctionsListUpdate body) {
        TenantContext.require();
        return http.ok(lists.updateList(id, http.tree(body), ifMatch), SanctionsList.class);
    }

    @Override
    public ResponseEntity<SanctionsList> sanctionsListsGet(UUID id) {
        return http.ok(catalog.requireList(id, false).toString(), SanctionsList.class);
    }

    @Override
    public ResponseEntity<SanctionsListPage> sanctionsListsList(Integer limit, String cursor, OffsetDateTime createdAfter,
            OffsetDateTime createdBefore, String source, String code, Boolean isActive, String customField1,
            String customField2, String customField3, String customField4, String customField5) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("source", source);
        f.put("code", code);
        f.put("is_active", isActive);
        return http.ok(lists.pageLists(f, limit, cursor, createdAfter, createdBefore), SanctionsListPage.class);
    }

    @Override
    public ResponseEntity<SanctionsListEntryPage> sanctionsListsEntries(UUID id, Integer limit, String cursor) {
        catalog.requireList(id, false);
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("sanctions_list_id", id);
        return http.ok(lists.pageEntries(f, limit, cursor, null, null), SanctionsListEntryPage.class);
    }

    @Override
    public ResponseEntity<SanctionsList> sanctionsListsSync(UUID id, String idempotencyKey, SanctionsListsSyncRequest body) {
        JsonNode req = http.tree(body);
        return http.once(idempotencyKey, body, SanctionsList.class, () -> http.ok(lists.sync(id, Http.text(req, "content"),
                Http.text(req, "format"), Http.text(req, "list_version"), Http.text(req, "published_at")), SanctionsList.class));
    }

    @Override
    public ResponseEntity<Map<String, Object>> sanctionsListsImport(UUID id, String idempotencyKey, SanctionsListsImportRequest body) {
        JsonNode req = http.tree(body);
        return http.onceMap(idempotencyKey, body, () -> lists.importFile(id, Db.uuid(req, "document_id"),
                Http.text(req, "content"), Http.text(req, "format")));
    }
}
