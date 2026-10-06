package io.meridian.archive.api;

import io.meridian.archive.api.generated.LegalHoldsApi;
import io.meridian.archive.api.generated.model.LegalHold;
import io.meridian.archive.api.generated.model.LegalHoldCreate;
import io.meridian.archive.api.generated.model.LegalHoldUpdate;
import io.meridian.archive.api.generated.model.LegalHoldsReleaseRequest;
import io.meridian.archive.application.LegalHoldService;
import io.meridian.archive.infrastructure.Rows;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Legal holds: placement, widening, maker-checker release, and the entity hold check. */
@RestController
public class LegalHoldsController implements LegalHoldsApi {
    private static final String TABLE = "legal_hold";

    private final LegalHoldService holds;
    private final Rows rows;
    private final CrudEngine crud;
    private final Updates updates;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public LegalHoldsController(LegalHoldService holds, Rows rows, CrudEngine crud, Updates updates, Idempotency idempotency,
                                JsonMapper json) {
        this.holds = holds;
        this.rows = rows;
        this.crud = crud;
        this.updates = updates;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<LegalHold> legalHoldsCreate(String idempotencyKey, LegalHoldCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, LegalHold.class, () -> ResponseEntity.status(HttpStatus.CREATED)
                .body(crud.read(holds.place(crud.toColumns(TABLE, body)), LegalHold.class)));
    }

    @Override
    public ResponseEntity<LegalHold> legalHoldsUpdate(UUID id, String ifMatch, LegalHoldUpdate body) {
        TenantContext.require();
        JsonNode current = rows.require(TABLE, id, false);
        ResponseEntity<LegalHold> r = updates.apply(TABLE, id, ifMatch, crud.toColumns(TABLE, body), LegalHold.class,
                (merged, changes) -> holds.prepareUpdate(current, changes));
        holds.afterUpdate(rows.requireText(TABLE, id, false));
        return r;
    }

    @Override
    public ResponseEntity<LegalHold> legalHoldsRelease(UUID id, String idempotencyKey, LegalHoldsReleaseRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        String reason = Http.required(req, "reason");
        return idempotency.execute(idempotencyKey, req, LegalHold.class,
                () -> ResponseEntity.ok(crud.read(holds.release(id, reason), LegalHold.class)));
    }

    /** {@code ?scope_type=CUSTOMER&id=...} -> {@code {scope_type, id, on_hold, holds:[...]}}. */
    @Override
    public ResponseEntity<Map<String, Object>> legalHoldsCheck(String scopeType, String id) {
        TenantContext.require();
        return ResponseEntity.ok(Http.object(json, holds.check(scopeType, id)));
    }
}
