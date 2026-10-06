package io.meridian.archive.api;

import io.meridian.archive.api.generated.ArchivedObjectsApi;
import io.meridian.archive.api.generated.model.ArchivedObjectsRestoreRequest;
import io.meridian.archive.application.CatalogService;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Archived objects are WORM manifests (no update/delete); they can be verified and restored to the query area. */
@RestController
public class ArchivedObjectsController implements ArchivedObjectsApi {
    private final CatalogService catalog;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public ArchivedObjectsController(CatalogService catalog, Idempotency idempotency, JsonMapper json) {
        this.catalog = catalog;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<Void> archivedObjectsRestore(UUID id, String idempotencyKey, ArchivedObjectsRestoreRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        String reason = Http.required(req, "reason");
        return Http.json(idempotency.execute(idempotencyKey, req, JsonNode.class,
                () -> ResponseEntity.ok(catalog.restore(id, reason))));
    }

    /** Verification is re-run on every call (a replayed result could hide later tampering). */
    @Override
    public ResponseEntity<Void> archivedObjectsVerify(UUID id, String idempotencyKey) {
        TenantContext.require();
        return Http.ok(catalog.verify(id));
    }
}
