package io.meridian.tenant.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.tenant.api.generated.TenantMovesApi;
import io.meridian.tenant.api.generated.model.TenantMove;
import io.meridian.tenant.api.generated.model.TenantMoveCreate;
import io.meridian.tenant.api.generated.model.TenantMovesCancelRequest;
import io.meridian.tenant.api.generated.model.TenantMovesConfirmCopyRequest;
import io.meridian.tenant.application.TenantMoveService;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tenant moves between cells (platform operator, S20): plan under maker-checker, then freeze, copy hand-off, switch,
 * unfreeze, or cancel before the switch. List and get are the generated CRUD defaults (operator-only reads).
 */
@RestController
public class TenantMovesController implements TenantMovesApi {
    private final TenantMoveService moves;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public TenantMovesController(TenantMoveService moves, Idempotency idempotency, JsonMapper json) {
        this.moves = moves;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<TenantMove> tenantMovesCreate(String idempotencyKey, TenantMoveCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, TenantMove.class,
                () -> Responses.of(HttpStatus.CREATED, moves.plan(body, idempotencyKey), TenantMove.class, json));
    }

    @Override
    public ResponseEntity<TenantMove> tenantMovesFreeze(UUID id, String idempotencyKey) {
        return step(idempotencyKey, "freeze:" + id, () -> moves.freeze(id));
    }

    @Override
    public ResponseEntity<TenantMove> tenantMovesStartCopy(UUID id, String idempotencyKey) {
        return step(idempotencyKey, "start-copy:" + id, () -> moves.startCopy(id));
    }

    @Override
    public ResponseEntity<TenantMove> tenantMovesConfirmCopy(UUID id, String idempotencyKey, TenantMovesConfirmCopyRequest body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, TenantMove.class, () -> Responses.of(HttpStatus.OK,
                moves.confirmCopy(id, body.getCheckpoint(), Boolean.TRUE.equals(body.getVerified())), TenantMove.class, json));
    }

    @Override
    public ResponseEntity<TenantMove> tenantMovesSwitch(UUID id, String idempotencyKey) {
        return step(idempotencyKey, "switch:" + id, () -> moves.switchPlacement(id));
    }

    @Override
    public ResponseEntity<TenantMove> tenantMovesUnfreeze(UUID id, String idempotencyKey) {
        return step(idempotencyKey, "unfreeze:" + id, () -> moves.unfreeze(id));
    }

    @Override
    public ResponseEntity<TenantMove> tenantMovesCancel(UUID id, String idempotencyKey, TenantMovesCancelRequest body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, TenantMove.class,
                () -> Responses.of(HttpStatus.OK, moves.cancel(id, body.getReason()), TenantMove.class, json));
    }

    private ResponseEntity<TenantMove> step(String idempotencyKey, String fingerprint, Supplier<JsonNode> action) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, fingerprint, TenantMove.class,
                () -> Responses.of(HttpStatus.OK, action.get(), TenantMove.class, json));
    }
}
