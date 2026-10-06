package io.meridian.document.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.document.api.generated.EsignRequestsApi;
import io.meridian.document.api.generated.model.EsignRequest;
import io.meridian.document.api.generated.model.EsignRequestCreate;
import io.meridian.document.api.generated.model.EsignRequestsCallbacksEventsRequest;
import io.meridian.document.api.generated.model.EsignRequestsCancelRequest;
import io.meridian.document.application.EsignService;
import io.meridian.document.config.RawBodyConfig;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * E-signature envelopes: create, send, remind, cancel, and the signed provider callback.
 * Contract: generated/openapi/services/document-service.openapi.yaml
 */
@RestController
public class EsignRequestsController implements EsignRequestsApi {
    private final EsignService esign;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;
    private final HttpServletRequest request;

    public EsignRequestsController(EsignService esign, CrudEngine crud, Idempotency idempotency, JsonMapper json,
                                   HttpServletRequest request) {
        this.esign = esign;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
        this.request = request;
    }

    @Override
    public ResponseEntity<EsignRequest> esignRequestsCreate(String idempotencyKey, EsignRequestCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, EsignRequest.class,
                () -> Responses.created(crud.read(esign.create(body), EsignRequest.class)));
    }

    @Override
    public ResponseEntity<EsignRequest> esignRequestsSend(UUID id, String idempotencyKey) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, Responses.op("send", id), EsignRequest.class,
                () -> ResponseEntity.ok(crud.read(esign.send(id), EsignRequest.class)));
    }

    @Override
    public ResponseEntity<EsignRequest> esignRequestsRemind(UUID id, String idempotencyKey) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, Responses.op("remind", id), EsignRequest.class,
                () -> ResponseEntity.ok(crud.read(esign.remind(id), EsignRequest.class)));
    }

    @Override
    public ResponseEntity<EsignRequest> esignRequestsCancel(UUID id, String idempotencyKey, EsignRequestsCancelRequest body) {
        TenantContext.require();
        String reason = json.valueToTree(body).path("reason").asString(null);
        return idempotency.execute(idempotencyKey, Responses.op("cancel", id, body), EsignRequest.class,
                () -> ResponseEntity.ok(crud.read(esign.cancel(id, reason), EsignRequest.class)));
    }

    @Override
    public ResponseEntity<Void> esignRequestsCallbacksEvents(String provider, String idempotencyKey,
                                                             EsignRequestsCallbacksEventsRequest body) {
        TenantContext.require();
        byte[] raw = RawBodyConfig.currentBody();
        String signature = request.getHeader(EsignService.SIGNATURE_HEADER);
        esign.verifySignature(raw, signature);
        JsonNode payload = json.valueToTree(body).get("payload");
        return Responses.erase(idempotency.execute(idempotencyKey,
                Map.of("op", "esign-callback", "provider", provider, "body", body), JsonNode.class,
                () -> ResponseEntity.ok(json.valueToTree(esign.callback(provider, raw, signature, payload)))));
    }
}
