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
import io.meridian.webhook.domain.SigningSecrets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Webhook endpoints: create (secret shown once), update, delete, enable/disable, secret rotation and test sends.
 * Plaintext secrets are added to the HTTP response only and never stored in the idempotency log, so an idempotent
 * replay of create/rotate returns the same resource without the secret.
 */
@RestController
public class WebhookEndpointsController implements WebhookEndpointsApi {
    private final EndpointService endpoints;
    private final DeliveryService deliveries;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public WebhookEndpointsController(EndpointService endpoints, DeliveryService deliveries, CrudEngine crud,
                                      Idempotency idempotency, JsonMapper json) {
        this.endpoints = endpoints;
        this.deliveries = deliveries;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<WebhookEndpoint> webhookEndpointsCreate(String idempotencyKey, WebhookEndpointCreate body) {
        TenantContext.require();
        AtomicReference<String> secret = new AtomicReference<>();
        ResponseEntity<WebhookEndpoint> r = idempotency.execute(idempotencyKey, body, WebhookEndpoint.class, () -> {
            EndpointService.WithSecret created = endpoints.create(body);
            secret.set(created.plaintextSecret());
            return ResponseEntity.status(HttpStatus.CREATED).body(crud.read(created.row(), WebhookEndpoint.class));
        });
        if (secret.get() == null) {
            return r;
        }
        ObjectNode out = json.valueToTree(r.getBody());
        out.put("signing_secret", secret.get());
        return withSecret(ResponseEntity.status(HttpStatus.CREATED)
                .eTag("\"" + out.path("row_version").asString() + "\"").body(out));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ResponseEntity<Void> asVoid(ResponseEntity<JsonNode> r) {
        return (ResponseEntity) r;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ResponseEntity<WebhookEndpoint> withSecret(ResponseEntity<ObjectNode> r) {
        return (ResponseEntity) r;
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
    public ResponseEntity<Void> webhookEndpointsRotateSecret(UUID id, String idempotencyKey) {
        TenantContext.require();
        AtomicReference<String> secret = new AtomicReference<>();
        ResponseEntity<JsonNode> r = idempotency.execute(idempotencyKey, Responses.op("rotate-secret", id, null),
                JsonNode.class, () -> {
                    EndpointService.WithSecret rotated = endpoints.rotateSecret(id);
                    secret.set(rotated.plaintextSecret());
                    JsonNode row = json.readTree(rotated.row());
                    ObjectNode out = json.createObjectNode();
                    out.put("id", id.toString());
                    out.put("signing_secret_version", row.get("signing_secret_version").asInt());
                    out.put("previous_secret_expires_at", Instant.now().plus(SigningSecrets.ROTATION_GRACE).toString());
                    return ResponseEntity.ok(out);
                });
        if (secret.get() == null) {
            return asVoid(r); // idempotent replay: same response (with Idempotent-Replayed) but no secret
        }
        ObjectNode out = ((ObjectNode) r.getBody()).deepCopy();
        out.put("signing_secret", secret.get());
        return Responses.body(HttpStatus.OK, out);
    }

    @Override
    public ResponseEntity<WebhookDelivery> webhookEndpointsTest(UUID id, String idempotencyKey,
                                                                WebhookEndpointsTestRequest body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, Responses.op("test", id, body), WebhookDelivery.class,
                () -> ResponseEntity.ok(crud.read(deliveries.test(id, body.getEventType()), WebhookDelivery.class)));
    }
}
