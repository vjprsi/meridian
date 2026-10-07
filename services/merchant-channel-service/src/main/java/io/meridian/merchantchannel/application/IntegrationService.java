package io.meridian.merchantchannel.application;

import static io.meridian.merchantchannel.application.CurrentUser.merchant;

import io.meridian.merchantchannel.application.Downstream.Query;
import io.meridian.merchantchannel.config.MerchantChannelProperties;
import io.meridian.common.error.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * API clients, keys and webhook endpoints of the merchant (OWNER users; creation with step-up). identity-service and
 * webhook-service do not know merchants, so the channel records which objects it created for which merchant
 * (merchant_object) and answers 404 for any other id. API clients may only hold the bank's allow-listed scopes for
 * merchant integrations ({@code meridian.merchant.api-client-scopes}); webhook subscriptions are limited to allowed
 * event types and always carry the filter {@code merchant_id == "<merchant>"}, so an endpoint only ever receives the
 * merchant's own events.
 */
@Service
public class IntegrationService {
    static final String IDENTITY = "identity-service";
    static final String WEBHOOKS = "webhook-service";
    static final String OBJECTS = "merchant_object";

    private final Downstream down;
    private final Access access;
    private final StepUp stepUp;
    private final ActionAudit audit;
    private final Rows rows;
    private final MerchantChannelProperties props;

    public IntegrationService(Downstream down, Access access, StepUp stepUp, ActionAudit audit, Rows rows,
                              MerchantChannelProperties props) {
        this.down = down;
        this.access = access;
        this.stepUp = stepUp;
        this.audit = audit;
        this.rows = rows;
        this.props = props;
    }

    // ------------------------------------------------------------------ API clients

    public JsonNode clients() {
        access.requireOwner("merchantAppApiClientsList", "API_CLIENT", null);
        return down.page(objects("API_CLIENT", "/v1/api-clients/"));
    }

    public JsonNode client(UUID id) {
        access.requireOwner("merchantAppApiClientsGet", "API_CLIENT", id);
        access.ownedObject("API_CLIENT", id, "merchantAppApiClientsGet");
        return down.get(IDENTITY, "/v1/api-clients/" + id);
    }

    public JsonNode createClient(JsonNode req, String key) {
        String action = "merchantAppApiClientsCreate";
        access.requireOwner(action, "API_CLIENT", null);
        String name = Req.required(req, "name");
        ObjectNode body = down.json().createObjectNode()
                .put("name", name.length() > 120 ? name.substring(0, 120) : name)
                .put("description", Req.text(req, "description"))
                .put("client_type", "SERVER")
                .put("auth_method", "PRIVATE_KEY_JWT")
                .put("environment", Req.text(req, "environment") == null ? "SANDBOX" : Req.text(req, "environment"))
                .put("owner_email", Req.text(req, "owner_email"))
                .put("fapi_profile", false);
        ArrayNode scopes = body.putArray("allowed_scopes");
        props.apiClientScopes().forEach(scopes::add);
        stepUp.require(action, "API_CLIENT", null);
        JsonNode created = down.post(IDENTITY, "/v1/api-clients", body, key, "create");
        record("API_CLIENT", created, name);
        audit.succeeded(action, "API_CLIENT", null, created.path("id").asString(null));
        return created;
    }

    public JsonNode suspendClient(UUID id, JsonNode req, String key) {
        String action = "merchantAppApiClientsSuspend";
        access.requireOwner(action, "API_CLIENT", id);
        access.ownedObject("API_CLIENT", id, action);
        JsonNode r = down.post(IDENTITY, "/v1/api-clients/" + id + "/suspend",
                down.json().createObjectNode().put("reason", Req.required(req, "reason")), key, "suspend");
        audit.succeeded(action, "API_CLIENT", id, null);
        return r;
    }

    public JsonNode keys(UUID id, Integer limit, String cursor) {
        access.requireOwner("merchantAppApiClientsKeys", "API_CLIENT", id);
        access.ownedObject("API_CLIENT", id, "merchantAppApiClientsKeys");
        return down.page(down.get(IDENTITY, "/v1/api-clients/" + id + "/keys", Query.none().paging(limit, cursor)),
                r -> id.toString().equals(r.path("api_client_id").asString(null)));
    }

    public JsonNode createKey(UUID id, JsonNode req, String key) {
        String action = "merchantAppApiClientsAddKeys";
        access.requireOwner(action, "API_CLIENT", id);
        access.ownedObject("API_CLIENT", id, action);
        ObjectNode body = down.json().createObjectNode().put("name", Req.required(req, "name"));
        if (Req.text(req, "expires_at") != null) {
            body.put("expires_at", Req.text(req, "expires_at"));
        }
        ArrayNode scopes = body.putArray("scopes");
        props.apiClientScopes().forEach(scopes::add);
        stepUp.require(action, "API_CLIENT", id);
        JsonNode created = down.post(IDENTITY, "/v1/api-clients/" + id + "/keys", body, key, "key");
        audit.succeeded(action, "API_CLIENT", id, created.path("id").asString(null));
        return created;
    }

    public JsonNode revokeKey(UUID id, UUID keyId, JsonNode req, String key) {
        String action = "merchantAppApiClientsKeysRevoke";
        access.requireOwner(action, "API_CLIENT", id);
        access.ownedObject("API_CLIENT", id, action);
        access.owned(IDENTITY, "/v1/api-clients/" + id + "/keys/" + keyId, "API_KEY", keyId, action,
                r -> id.toString().equals(r.path("api_client_id").asString(null)));
        JsonNode r = down.post(IDENTITY, "/v1/api-clients/" + id + "/keys/" + keyId + "/revoke",
                down.json().createObjectNode().put("reason", Req.required(req, "reason")), key, "revoke");
        audit.succeeded(action, "API_CLIENT", id, keyId);
        return r;
    }

    // ------------------------------------------------------------------ webhooks

    public JsonNode endpoints() {
        access.requireOwner("merchantAppWebhooksList", "WEBHOOK_ENDPOINT", null);
        return down.page(objects("WEBHOOK_ENDPOINT", "/v1/webhook-endpoints/"));
    }

    public JsonNode endpoint(UUID id) {
        access.requireOwner("merchantAppWebhooksGet", "WEBHOOK_ENDPOINT", id);
        access.ownedObject("WEBHOOK_ENDPOINT", id, "merchantAppWebhooksGet");
        return down.get(WEBHOOKS, "/v1/webhook-endpoints/" + id);
    }

    public JsonNode createEndpoint(JsonNode req, String key) {
        String action = "merchantAppWebhooksCreate";
        access.requireOwner(action, "WEBHOOK_ENDPOINT", null);
        UUID client = Req.uuid(req, "api_client_id", true);
        access.ownedObject("API_CLIENT", client, action);
        String url = Req.required(req, "url");
        if (!url.startsWith("https://")) {
            throw ApiException.unprocessable("https_required", "Webhook endpoints must use https");
        }
        ObjectNode body = down.json().createObjectNode()
                .put("url", url)
                .put("description", Req.text(req, "description"))
                .put("api_client_id", client.toString())
                .put("environment", Req.text(req, "environment") == null ? "SANDBOX" : Req.text(req, "environment"));
        stepUp.require(action, "WEBHOOK_ENDPOINT", null);
        JsonNode created = down.post(WEBHOOKS, "/v1/webhook-endpoints", body, key, "create");
        record("WEBHOOK_ENDPOINT", created, url);
        audit.succeeded(action, "WEBHOOK_ENDPOINT", null, created.path("id").asString(null));
        return created;
    }

    public void deleteEndpoint(UUID id) {
        String action = "merchantAppWebhooksDelete";
        access.requireOwner(action, "WEBHOOK_ENDPOINT", id);
        access.ownedObject("WEBHOOK_ENDPOINT", id, action);
        down.delete(WEBHOOKS, "/v1/webhook-endpoints/" + id);
        audit.succeeded(action, "WEBHOOK_ENDPOINT", id, null);
    }

    public JsonNode endpointState(UUID id, String op, JsonNode req, String key) {
        String action = op.equals("enable") ? "merchantAppWebhooksEnable" : "merchantAppWebhooksDisable";
        access.requireOwner(action, "WEBHOOK_ENDPOINT", id);
        access.ownedObject("WEBHOOK_ENDPOINT", id, action);
        ObjectNode body = null;
        if (op.equals("disable")) {
            String reason = Req.text(req, "reason");
            body = down.json().createObjectNode().put("reason", reason == null ? "Disabled in the merchant app" : reason);
        }
        JsonNode r = down.post(WEBHOOKS, "/v1/webhook-endpoints/" + id + "/" + op, body, key, op);
        audit.succeeded(action, "WEBHOOK_ENDPOINT", id, null);
        return r;
    }

    public JsonNode subscriptions(UUID id, Integer limit, String cursor) {
        access.requireOwner("merchantAppWebhooksSubscriptions", "WEBHOOK_ENDPOINT", id);
        access.ownedObject("WEBHOOK_ENDPOINT", id, "merchantAppWebhooksSubscriptions");
        return down.page(down.get(WEBHOOKS, "/v1/webhook-subscriptions",
                        Query.of("webhook_endpoint_id", id).paging(limit, cursor)),
                r -> id.toString().equals(r.path("webhook_endpoint_id").asString(null)));
    }

    public JsonNode subscribe(UUID id, JsonNode req, String key) {
        String action = "merchantAppWebhooksAddSubscriptions";
        access.requireOwner(action, "WEBHOOK_ENDPOINT", id);
        access.ownedObject("WEBHOOK_ENDPOINT", id, action);
        String type = Req.required(req, "event_type");
        if (!allowedEventType(type, props.webhookEventTypes())) {
            throw ApiException.unprocessable("event_type_not_allowed", "Merchant endpoints may subscribe to "
                    + props.webhookEventTypes());
        }
        JsonNode created = down.post(WEBHOOKS, "/v1/webhook-subscriptions", down.json().createObjectNode()
                .put("webhook_endpoint_id", id.toString())
                .put("event_type", type)
                .put("filter_expression", "merchant_id == \"" + merchant() + "\"")
                .put("is_active", true), key, "subscribe");
        audit.succeeded(action, "WEBHOOK_ENDPOINT", id, created.path("id").asString(null));
        return created;
    }

    public JsonNode deliveries(UUID id, Integer limit, String cursor) {
        access.requireOwner("merchantAppWebhooksDeliveries", "WEBHOOK_ENDPOINT", id);
        access.ownedObject("WEBHOOK_ENDPOINT", id, "merchantAppWebhooksDeliveries");
        return down.page(down.get(WEBHOOKS, "/v1/webhook-deliveries",
                        Query.of("webhook_endpoint_id", id).paging(limit, cursor)),
                r -> id.toString().equals(r.path("webhook_endpoint_id").asString(null)));
    }

    /** An exact allowed type, or a type (or narrower wildcard) under an allowed {@code <prefix>.*}. */
    static boolean allowedEventType(String type, List<String> allowed) {
        if (!type.matches("[a-z0-9_]+(\\.[a-z0-9_]+)*(\\.\\*)?")) {
            return false;
        }
        for (String a : allowed) {
            if (a.equals(type)) {
                return true;
            }
            if (a.endsWith(".*")) {
                String prefix = a.substring(0, a.length() - 1);
                if (type.startsWith(prefix) && type.length() > prefix.length()) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ helpers

    private List<JsonNode> objects(String type, String path) {
        String service = type.equals("API_CLIENT") ? IDENTITY : WEBHOOKS;
        List<JsonNode> out = new ArrayList<>();
        for (ObjectNode o : rows.list("select row_to_json(t)::text from merchant_object t where t.merchant_id = ? "
                + "and t.object_type = ? order by t.id desc limit 200", merchant(), type)) {
            try {
                out.add(down.get(service, path + o.path("object_id").asString()));
            } catch (ApiException e) {
                if (e.status() != 404) {
                    throw e;
                }
            }
        }
        return out;
    }

    private void record(String type, JsonNode created, String label) {
        String id = created.path("id").asString(null);
        if (id == null) {
            return;
        }
        if (rows.one("select row_to_json(t)::text from merchant_object t where t.object_id = ?", UUID.fromString(id))
                .isPresent()) {
            return;       // an idempotent replay of the same creation
        }
        rows.insert(OBJECTS, rows.object().put("merchant_id", merchant().toString()).put("object_type", type)
                .put("object_id", id).put("label", label.length() > 160 ? label.substring(0, 160) : label));
    }
}
