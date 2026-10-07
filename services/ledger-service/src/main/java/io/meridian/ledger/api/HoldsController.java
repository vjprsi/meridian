package io.meridian.ledger.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.ledger.api.generated.HoldsApi;
import io.meridian.ledger.api.generated.model.Hold;
import io.meridian.ledger.api.generated.model.HoldCreate;
import io.meridian.ledger.api.generated.model.HoldEventPage;
import io.meridian.ledger.api.generated.model.HoldsCaptureRequest;
import io.meridian.ledger.api.generated.model.HoldsIncrementRequest;
import io.meridian.ledger.api.generated.model.HoldsReleaseRequest;
import io.meridian.ledger.application.HoldService;
import io.meridian.ledger.domain.PaymentRefs;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Holds: reserve, increment, capture into postings, release. */
@RestController
public class HoldsController implements HoldsApi {
    private final HoldService holds;
    private final CrudEngine crud;
    private final JsonMapper json;

    public HoldsController(HoldService holds, CrudEngine crud, JsonMapper json) {
        this.holds = holds;
        this.crud = crud;
        this.json = json;
    }

    @Override
    public ResponseEntity<Hold> holdsCreate(String idempotencyKey, HoldCreate body) {
        TenantContext.require();
        return ResponseEntity.status(HttpStatus.CREATED).body(crud.read(holds.place(body, idempotencyKey), Hold.class));
    }

    @Override
    public ResponseEntity<Hold> holdsIncrement(UUID id, String idempotencyKey, HoldsIncrementRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        return ok(holds.increment(id, new BigDecimal(req.get("amount").asString()), text(req, "reason")));
    }

    @Override
    public ResponseEntity<Hold> holdsCapture(UUID id, String idempotencyKey, HoldsCaptureRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        return ok(holds.capture(id, new BigDecimal(req.get("amount").asString()),
                UUID.fromString(req.get("counter_account_id").asString()), text(req, "description"),
                req.hasNonNull("release_remainder") && req.get("release_remainder").asBoolean(), idempotencyKey,
                text(req, "reference"), PaymentRefs.parse(req.get("external_refs"))));
    }

    @Override
    public ResponseEntity<Hold> holdsRelease(UUID id, String idempotencyKey, HoldsReleaseRequest body) {
        TenantContext.require();
        JsonNode req = body == null ? json.createObjectNode() : json.valueToTree(body);
        return ok(holds.release(id, text(req, "reason")));
    }

    @Override
    public ResponseEntity<HoldEventPage> holdsEvents(UUID id, Integer limit, String cursor) {
        return crud.list("hold_event", HoldEventPage.class,
                Crud.params().with("hold_id", id).with("limit", limit).with("cursor", cursor));
    }

    private ResponseEntity<Hold> ok(String row) {
        return ResponseEntity.ok(crud.read(row, Hold.class));
    }

    private static String text(JsonNode n, String f) {
        return n.hasNonNull(f) ? n.get(f).asString() : null;
    }
}
