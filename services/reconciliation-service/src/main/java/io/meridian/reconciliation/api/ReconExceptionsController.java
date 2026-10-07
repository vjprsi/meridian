package io.meridian.reconciliation.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.reconciliation.api.generated.ReconExceptionsApi;
import io.meridian.reconciliation.api.generated.model.ReconException;
import io.meridian.reconciliation.api.generated.model.ReconExceptionUpdate;
import io.meridian.reconciliation.api.generated.model.ReconExceptionsAssignRequest;
import io.meridian.reconciliation.api.generated.model.ReconExceptionsResolveRequest;
import io.meridian.reconciliation.api.generated.model.ReconMatch;
import io.meridian.reconciliation.application.ExceptionService;
import io.meridian.reconciliation.application.Fields;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reconciliation breaks: assign, resolve (optionally with a ledger adjustment), accept the suggested match. */
@RestController
public class ReconExceptionsController implements ReconExceptionsApi {
    private final ExceptionService exceptions;
    private final Idempotency idempotency;
    private final Responses responses;
    private final JsonMapper json;

    public ReconExceptionsController(ExceptionService exceptions, Idempotency idempotency, Responses responses,
                                     JsonMapper json) {
        this.exceptions = exceptions;
        this.idempotency = idempotency;
        this.responses = responses;
        this.json = json;
    }

    @Override
    public ResponseEntity<ReconException> reconExceptionsUpdate(UUID id, String ifMatch, ReconExceptionUpdate body) {
        TenantContext.require();
        return responses.ok(exceptions.update(id, ifMatch, body), ReconException.class);
    }

    @Override
    public ResponseEntity<ReconException> reconExceptionsAssign(UUID id, String idempotencyKey,
                                                                ReconExceptionsAssignRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        return idempotency.execute(idempotencyKey, responses.action("assign", id, req), ReconException.class,
                () -> responses.ok(exceptions.assign(id, Fields.text(req, "assigned_to")), ReconException.class));
    }

    @Override
    public ResponseEntity<ReconException> reconExceptionsResolve(UUID id, String idempotencyKey,
                                                                 ReconExceptionsResolveRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        return idempotency.execute(idempotencyKey, responses.action("resolve", id, req), ReconException.class,
                () -> responses.ok(exceptions.resolve(id, Fields.text(req, "resolution_note")), ReconException.class));
    }

    @Override
    public ResponseEntity<ReconMatch> reconExceptionsAcceptSuggestion(UUID id, String idempotencyKey) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, responses.action("accept-suggestion", id, null), ReconMatch.class,
                () -> responses.ok(exceptions.acceptSuggestion(id), ReconMatch.class));
    }
}
