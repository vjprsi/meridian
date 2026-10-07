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

/**
 * E-signature envelopes: create, send, remind, cancel, and the public, HMAC-signed provider callback.
 * Contract: generated/openapi/services/document-service.openapi.yaml
 */
@RestController
public class EsignRequestsController implements EsignRequestsApi {
    private final EsignService esign;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final HttpServletRequest request;

    public EsignRequestsController(EsignService esign, CrudEngine crud, Idempotency idempotency,
                                   HttpServletRequest request) {
        this.esign = esign;
        this.crud = crud;
        this.idempotency = idempotency;
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
        return idempotency.execute(idempotencyKey, Responses.op("cancel", id, body), EsignRequest.class,
                () -> ResponseEntity.ok(crud.read(esign.cancel(id, body.getReason()), EsignRequest.class)));
    }

    /**
     * Public provider callback (no token, no tenant header, no Idempotency-Key): the HMAC over the exact raw bytes
     * is verified first, then the owning tenant is resolved from the envelope and the event applied as that tenant.
     * Redelivered events are absorbed by the state machine (outcome IGNORED / DUPLICATE).
     */
    @Override
    public ResponseEntity<Map<String, Object>> esignRequestsCallbacksEvents(String provider,
                                                                           EsignRequestsCallbacksEventsRequest body) {
        esign.verifySignature(provider, RawBodyConfig.currentBody(), request.getHeader(EsignService.SIGNATURE_HEADER));
        EsignService.CallbackRoute route = esign.route(provider, body.getEnvelopeId());
        EsignService.CallbackEvent event = new EsignService.CallbackEvent(body.getEnvelopeId(), body.getEvent(),
                body.getSignerEmail(), body.getSignedDocumentId(), body.getReason());
        try {
            return ResponseEntity.ok(TenantContext.callAs(route.tenantId(), "esign-callback:" + provider,
                    () -> esign.callback(provider, route.esignRequestId(), event)));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
