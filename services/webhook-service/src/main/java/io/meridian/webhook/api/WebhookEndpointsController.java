package io.meridian.webhook.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import io.meridian.webhook.api.generated.WebhookEndpointsApi;
import io.meridian.webhook.api.generated.model.WebhookDelivery;
import io.meridian.webhook.api.generated.model.WebhookEndpoint;
import io.meridian.webhook.api.generated.model.WebhookEndpointCreate;
import io.meridian.webhook.api.generated.model.WebhookEndpointUpdate;
import io.meridian.webhook.api.generated.model.WebhookEndpointsDisableRequest;
import io.meridian.webhook.api.generated.model.WebhookEndpointsTestRequest;
import io.meridian.webhook.application.DeliveryService;
import io.meridian.webhook.application.EndpointService;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhook endpoints: create (secret shown once), update, delete, enable/disable, secret rotation and test sends.
 * The plaintext secret ({@code signing_secret}, response-only) is set on the response after the idempotency log
 * has stored it, so an idempotent replay of create/rotate returns the same resource without the secret.
 */
@RestController
public class WebhookEndpointsController implements WebhookEndpointsApi {
    private final EndpointService endpoints;
    private final DeliveryService deliveries;
    private final CrudEngine crud;
    private final Idempotency idempotency;

    public WebhookEndpointsController(EndpointService endpoints, DeliveryService deliveries, CrudEngine crud,
                                      Idempotency idempotency) {
        this.endpoints = endpoints;
        this.deliveries = deliveries;
        this.crud = crud;
        this.idempotency = idempotency;
    }

    @Override
    public ResponseEntity<WebhookEndpoint> webhookEndpointsCreate(String idempotencyKey, WebhookEndpointCreate body) {
        TenantContext.require();
        AtomicReference<String> secret = new AtomicReference<>();
        ResponseEntity<WebhookEndpoint> r = idempotency.execute(idempotencyKey, body, WebhookEndpoint.class, () -> {
            EndpointService.WithSecret created = endpoints.create(body);
            secret.set(created.plaintextSecret());
            WebhookEndpoint ep = crud.read(created.row(), WebhookEndpoint.class);
            return ResponseEntity.status(HttpStatus.CREATED).eTag("\"" + ep.getRowVersion() + "\"").body(ep);
        });
        return withSecret(r, secret.get());
    }

    @Override
    public ResponseEntity<WebhookEndpoint> webhookEndpointsUpdate(UUID id, String ifMatch, WebhookEndpointUpdate body) {
        TenantContext.require();
        endpoints.validateUpdate(id, body);
        return crud.update("webhook_endpoint", WebhookEndpoint.class, body, ifMatch, Crud.params().with("id", id));
    }

    @Override
    public ResponseEntity<Void> webhookEndpointsDelete(UUID id) {
        TenantContext.require();
        endpoints.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<WebhookEndpoint> webhookEndpointsEnable(UUID id, String idempotencyKey) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, Responses.op("enable", id, null), WebhookEndpoint.class,
                () -> ResponseEntity.ok(crud.read(endpoints.enable(id), WebhookEndpoint.class)));
    }

    @Override
    public ResponseEntity<WebhookEndpoint> webhookEndpointsDisable(UUID id, String idempotencyKey,
                                                                   WebhookEndpointsDisableRequest body) {
        TenantContext.require();
        String reason = body == null ? null : body.getReason();
        return idempotency.execute(idempotencyKey, Responses.op("disable", id, body), WebhookEndpoint.class,
                () -> ResponseEntity.ok(crud.read(endpoints.disable(id, reason), WebhookEndpoint.class)));
    }

    @Override
    public ResponseEntity<WebhookEndpoint> webhookEndpointsRotateSecret(UUID id, String idempotencyKey) {
        TenantContext.require();
        AtomicReference<String> secret = new AtomicReference<>();
        ResponseEntity<WebhookEndpoint> r = idempotency.execute(idempotencyKey, Responses.op("rotate-secret", id, null),
                WebhookEndpoint.class, () -> {
                    EndpointService.WithSecret rotated = endpoints.rotateSecret(id);
                    secret.set(rotated.plaintextSecret());
                    return ResponseEntity.ok(crud.read(rotated.row(), WebhookEndpoint.class));
                });
        return withSecret(r, secret.get());
    }

    @Override
    public ResponseEntity<WebhookDelivery> webhookEndpointsTest(UUID id, String idempotencyKey,
                                                                WebhookEndpointsTestRequest body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, Responses.op("test", id, body), WebhookDelivery.class,
                () -> ResponseEntity.ok(crud.read(deliveries.test(id, body.getEventType()), WebhookDelivery.class)));
    }

    /** Adds the one-time plaintext secret to a freshly executed (not replayed) response. */
    private static ResponseEntity<WebhookEndpoint> withSecret(ResponseEntity<WebhookEndpoint> r, String secret) {
        if (secret != null && r.getBody() != null) {
            r.getBody().setSigningSecret(secret);
        }
        return r;
    }
}
