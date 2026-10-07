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
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import io.meridian.web.outbound.OutboundUrlPolicy;
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
    private final OutboundUrlPolicy urlPolicy;

    public EndpointService(WebhookStore store, CrudEngine crud, SecretCipher cipher, OutboxWriter outbox, JsonMapper json,
                           @Value("${meridian.webhooks.allow-insecure-localhost:false}") boolean allowInsecureLocalhost,
                           OutboundUrlPolicy urlPolicy) {
        // http://localhost receivers only in dev mode (the shared policy ignores the relaxation otherwise)
        this.urlPolicy = urlPolicy.relaxedForDev(allowInsecureLocalhost, allowInsecureLocalhost);
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
        checkDestination(values.path("url").asString(""));
        UUID id = Ids.newId();
        SigningSecrets secrets = SigningSecrets.fresh();
        values.put("id", id.toString());
        values.put("status", "ACTIVE"); // status is managed by enable/disable actions and delivery health
        values.put("signing_secret_enc", cipher.encrypt(secrets.current(), id.toString()));
        values.put("signing_secret_version", 1);
        values.put("consecutive_failures", 0);
        return new WithSecret(crud.insert("webhook_endpoint", values), secrets.current());
    }

    /** Validates a PATCH before it is applied generically (status is read-only: it changes through actions). */
    public void validateUpdate(UUID id, Object body) {
        ObjectNode values = crud.toColumns("webhook_endpoint", body);
        WebhookRules.validateUrl(values.has("url") ? values.get("url").asString() : "https://unchanged.invalid",
                allowInsecureLocalhost);
        validateHeaders(values.get("custom_headers"));
        if (values.has("url")) {
            checkDestination(values.get("url").asString());
        }
    }

    /**
     * SEC-09: the endpoint must be a public https destination under the shared outbound policy. Literal internal
     * hosts and names resolving to internal addresses are refused now; a name that does not resolve yet is accepted
     * (DNS may be set up later) because every delivery re-checks the resolved addresses at connect time.
     */
    void checkDestination(String url) {
        try {
            urlPolicy.resolveAllowed(urlPolicy.checkSyntax(url).getHost());
        } catch (OutboundUrlPolicy.RejectedException e) {
            switch (e.code()) {
                case "unresolvable_host" -> { }
                case "https_required" -> throw ApiException.unprocessable("https_required", "Webhook endpoints must use https");
                case "destination_not_allowed" -> throw ApiException.unprocessable("url_not_allowed",
                        "Webhook endpoints must be public https URLs; internal, private and metadata addresses are not allowed");
                default -> throw ApiException.unprocessable("invalid_url", e.getMessage());
            }
        }
    }

    @Transactional
    public String enable(UUID id) {
        WebhookStore.EndpointRow e = store.requireEndpoint(id, true);
        store.setEndpointStatus(id, WebhookRules.enable(e.status()), null, true);
        outbox.publish("platform.webhook_endpoint.enabled", "webhook_endpoint", id,
                json.readTree(store.endpointEventJson(id)));
        return store.endpointJson(id);
    }

    @Transactional
    public String disable(UUID id, String reason) {
        WebhookStore.EndpointRow e = store.requireEndpoint(id, true);
        String r = reason == null || reason.isBlank() ? "Disabled by " + io.meridian.common.tenancy.TenantContext.principal() : reason;
        store.setEndpointStatus(id, WebhookRules.disable(e.status()), r, false);
        outbox.publish("platform.webhook_endpoint.disabled", "webhook_endpoint", id,
                json.readTree(store.endpointEventJson(id)));
        return store.endpointJson(id);
    }

    /** Issues a new secret; the previous one stays valid (signatures carry both) for 24 hours. */
    @Transactional
    public WithSecret rotateSecret(UUID id) {
        WebhookStore.EndpointRow e = store.requireEndpoint(id, true);
        SigningSecrets rotated = secrets(e).rotate(Instant.now());
        store.updateSecret(id, cipher.encrypt(rotated.current(), id.toString()), e.secretVersion() + 1,
                cipher.encrypt(rotated.previous(), id.toString()),
                rotated.previousExpiresAt().atOffset(ZoneOffset.UTC));
        outbox.publish("platform.webhook_endpoint.secret_rotated", "webhook_endpoint", id,
                json.readTree(store.endpointEventJson(id)));
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
        String aad = e.id().toString();
        String previous = e.previousSecretBlob() == null ? null : cipher.decrypt(e.previousSecretBlob(), aad);
        return new SigningSecrets(cipher.decrypt(e.secretBlob(), aad), previous,
                e.previousSecretExpiresAt() == null ? null : e.previousSecretExpiresAt().toInstant());
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
