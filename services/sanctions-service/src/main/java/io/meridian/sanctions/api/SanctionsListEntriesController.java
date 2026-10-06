package io.meridian.sanctions.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.sanctions.api.generated.SanctionsListEntriesApi;
import io.meridian.sanctions.api.generated.model.SanctionsListEntry;
import io.meridian.sanctions.api.generated.model.SanctionsListEntryCreate;
import io.meridian.sanctions.api.generated.model.SanctionsListEntryPage;
import io.meridian.sanctions.api.generated.model.SanctionsListEntryUpdate;
import io.meridian.sanctions.application.ListCatalog;
import io.meridian.sanctions.application.ListService;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** List entries; visibility follows the list (global or own), changes only on the tenant's own lists. */
@RestController
public class SanctionsListEntriesController implements SanctionsListEntriesApi {
    private final ListService lists;
    private final ListCatalog catalog;
    private final Http http;

    public SanctionsListEntriesController(ListService lists, ListCatalog catalog, Http http) {
        this.lists = lists;
        this.catalog = catalog;
        this.http = http;
    }

    @Override
    public ResponseEntity<SanctionsListEntry> sanctionsListEntriesCreate(String idempotencyKey, SanctionsListEntryCreate body) {
        return http.once(idempotencyKey, body, SanctionsListEntry.class,
                () -> http.respond(HttpStatus.CREATED, lists.createEntry(http.tree(body)), SanctionsListEntry.class));
    }

    @Override
    public ResponseEntity<SanctionsListEntry> sanctionsListEntriesUpdate(UUID id, String ifMatch, SanctionsListEntryUpdate body) {
        TenantContext.require();
        return http.ok(lists.updateEntry(id, http.tree(body), ifMatch), SanctionsListEntry.class);
    }

    @Override
    public ResponseEntity<SanctionsListEntry> sanctionsListEntriesGet(UUID id) {
        return http.ok(catalog.requireEntry(id).toString(), SanctionsListEntry.class);
    }

    @Override
    public ResponseEntity<SanctionsListEntryPage> sanctionsListEntriesList(Integer limit, String cursor,
            OffsetDateTime createdAfter, OffsetDateTime createdBefore, UUID sanctionsListId, String entryType,
            String nationality, String externalId, String customField1, String customField2, String customField3,
            String customField4, String customField5) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("sanctions_list_id", sanctionsListId);
        f.put("entry_type", entryType);
        f.put("nationality", nationality);
        f.put("external_id", externalId);
        return http.ok(lists.pageEntries(f, limit, cursor, createdAfter, createdBefore), SanctionsListEntryPage.class);
    }
}
