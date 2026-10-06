package io.meridian.webhook.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.events.OutboxWriter;
import io.meridian.web.crud.CrudEngine;
import io.meridian.webhook.domain.EventTypePattern;
import io.meridian.webhook.domain.PayloadFilter;
import io.meridian.webhook.domain.SigningSecrets;
import io.meridian.webhook.domain.WebhookRules;
import io.meridian.webhook.infrastructure.SecretCipher;
import io.meridian.webhook.infrastructure.WebhookStore;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Endpoint lifecycle (create with secret, update, enable/disable, rotate secret, delete) and subscriptions. */
@Service
public class EndpointService {
    private final WebhookStore store;
    private final CrudEngine crud;
    private final SecretCipher cipher;
    private final OutboxWriter outbox;
    private final JsonMapper json;
    private final boolean allowInsecureLocalhost;

    public EndpointService(WebhookStore store, CrudEngine crud, SecretCipher cipher, OutboxWriter outbox, JsonMapper json,
                           @Value("${meridian.webhooks.allow-insecure-localhost:false}") boolean allowInsecureLocalhost) {
        this.store = store;
        this.crud = crud;
        this.cipher = cipher;
        this.outbox = outbox;
        this.json = json;
        this.allowInsecureLocalhost = allowInsecureLocalhost;
    }

    /** Row JSON plus the plaintext secret, which is shown exactly once. */
    public record WithSecret(String row, String plaintextSecret) {}

    // ------------------------------------------------------------------ endpoints

    @Transactional
    public WithSecret create(Object body) {
        ObjectNode values = crud.toColumns("webhook_endpoint", body);
        WebhookRules.validateUrl(values.path("url").asString(""), allowInsecureLocalhost);
        validateHeaders(values.get("custom_headers"));
        UUID id = Ids.newId();
        SigningSecrets secrets = SigningSecrets.fresh();
        values.put("id", id.toString());
        values.put("status", "ACTIVE"); // status is managed by enable/disable actions and delivery health
        values.put("signing_secret", cipher.encrypt(secrets.serialise(), id.toString()));
        values.put("signing_secret_version", 1);
        values.put("consecutive_failures", 0);
        return new WithSecret(crud.insert("webhook_endpoint", values), secrets.current());
    }

    /** Validates a PATCH before it is applied generically; status may only change through actions. */
    public void validateUpdate(UUID id, Object body) {
        ObjectNode values = crud.toColumns("webhook_endpoint", body);
        WebhookRules.validateUrl(values.has("url") ? values.get("url").asString() : "https://unchanged.invalid",
                allowInsecureLocalhost);
        validateHeaders(values.get("custom_headers"));
        if (values.has("status")) {
            String current = store.requireEndpoint(id, false).status();
            if (!current.equals(values.get("status").asString())) {
                throw ApiException.conflict("invalid_transition",
                        "Endpoint status changes go through /enable and /disable");
            }
        }
    }

    @Transactional
    public String enable(UUID id) {
        WebhookStore.EndpointRow e = store.requireEndpoint(id, true);
        store.setEndpointStatus(id, WebhookRules.enable(e.status()), null, true);
        return store.endpointJson(id);
    }

    @Transactional
    public String disable(UUID id, String reason) {
        WebhookStore.EndpointRow e = store.requireEndpoint(id, true);
        String r = reason == null || reason.isBlank() ? "Disabled by " + io.meridian.common.tenancy.TenantContext.principal() : reason;
        store.setEndpointStatus(id, WebhookRules.disable(e.status()), r, false);
        String row = store.endpointJson(id);
        outbox.publish("platform.webhook_endpoint.disabled", "webhook_endpoint", id, json.readTree(row));
        return row;
    }

    /** Issues a new secret; the previous one stays valid (signatures carry both) for 24 hours. */
    @Transactional
    public WithSecret rotateSecret(UUID id) {
        WebhookStore.EndpointRow e = store.requireEndpoint(id, true);
        SigningSecrets rotated = secrets(e).rotate(Instant.now());
        store.updateSecret(id, cipher.encrypt(rotated.serialise(), id.toString()), e.secretVersion() + 1);
        return new WithSecret(store.endpointJson(id), rotated.current());
    }

    @Transactional
    public void delete(UUID id) {
        store.requireEndpoint(id, true);
        if (store.endpointHasDeliveries(id)) {
            throw ApiException.conflict("endpoint_has_deliveries",
                    "Endpoint has delivery history that must be retained; disable it instead");
        }
        store.deleteEndpointCascade(id);
    }

    public SigningSecrets secrets(WebhookStore.EndpointRow e) {
        return SigningSecrets.parse(cipher.decrypt(e.secretBlob(), e.id().toString()));
    }

    private void validateHeaders(JsonNode headers) {
        if (headers == null || headers.isNull()) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = json.treeToValue(headers, Map.class);
        WebhookRules.validateCustomHeaders(map);
    }

    // ------------------------------------------------------------------ subscriptions

    public void validateSubscription(Object body, Optional<UUID> existingId) {
        ObjectNode v = crud.toColumns("webhook_subscription", body);
        if (v.has("event_type") && !EventTypePattern.isValid(v.get("event_type").asString())) {
            throw ApiException.unprocessable("invalid_event_type",
                    "event_type must be an event type, a prefix wildcard like 'payments.*', or '*'");
        }
        if (v.has("filter_expression")) {
            PayloadFilter.parse(v.get("filter_expression").asString());
        }
        if (existingId.isEmpty()) {
            UUID endpointId = UUID.fromString(v.get("webhook_endpoint_id").asString());
            if (store.endpoint(endpointId, false).isEmpty()) {
                throw ApiException.unprocessable("endpoint_not_found", "Webhook endpoint " + endpointId + " does not exist");
            }
        } else if (v.has("webhook_endpoint_id")) {
            throw ApiException.unprocessable("immutable_field", "webhook_endpoint_id cannot be changed");
        }
    }
}
