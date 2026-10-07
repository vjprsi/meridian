package io.meridian.businesschannel.application;

import static io.meridian.businesschannel.application.CurrentUser.business;

import io.meridian.businesschannel.application.Downstream.Query;
import io.meridian.businesschannel.config.BusinessChannelProperties;
import io.meridian.common.error.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * API clients, keys and webhook endpoints of the business (ADMIN users; creation with step-up). identity-service and
 * webhook-service do not know businesses, so the channel records which objects it created for which business
 * (business_object) and answers 404 for any other id. API clients may only hold the bank's allow-listed scopes for
 * business integrations ({@code meridian.business.api-client-scopes}); webhook subscriptions are limited to allowed
 * event types and always carry the filter {@code customer_id == "<business>"}, so an endpoint only ever receives the
 * business's own events.
 *
 * <p>Machine-to-machine access (docs/BUSINESS_M2M_API.md): every API client is created together with a TECHNICAL
 * portal user of the business (role MAKER or VIEWER; entitlements set by an admin like any user's) and tagged in
 * identity-service with {@code business_customer_id} and {@code business_user_id}; its client-credentials tokens carry
 * those claims, scope channels:business and the m2m marker, and act with that user's role and entitlements (never
 * approving or administering). Suspending the client suspends its technical user.
 */
@Service
public class IntegrationService {
    static final String IDENTITY = "identity-service";
    static final String WEBHOOKS = "webhook-service";
    static final String OBJECTS = "business_object";
    static final java.util.Set<String> TECHNICAL_ROLES = java.util.Set.of("MAKER", "VIEWER");
    static final java.util.Set<String> AUTH_METHODS = java.util.Set.of("CLIENT_SECRET_BASIC", "PRIVATE_KEY_JWT");

    private final Downstream down;
    private final Access access;
    private final StepUp stepUp;
    private final ActionAudit audit;
    private final Rows rows;
    private final BusinessChannelProperties props;
    private final io.meridian.events.OutboxWriter outbox;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    public IntegrationService(Downstream down, Access access, StepUp stepUp, ActionAudit audit, Rows rows,
                              BusinessChannelProperties props, io.meridian.events.OutboxWriter outbox,
                              org.springframework.transaction.support.TransactionTemplate tx) {
        this.outbox = outbox;
        this.tx = tx;
        this.down = down;
        this.access = access;
        this.stepUp = stepUp;
        this.audit = audit;
        this.rows = rows;
        this.props = props;
    }

    // ------------------------------------------------------------------ API clients

    public JsonNode clients() {
        access.requireAdmin("businessApiClientsList", "API_CLIENT", null);
        return down.page(objects("API_CLIENT", "/v1/api-clients/"));
    }

    public JsonNode client(UUID id) {
        access.requireAdmin("businessApiClientsGet", "API_CLIENT", id);
        access.ownedObject("API_CLIENT", id, "businessApiClientsGet");
        return down.get(IDENTITY, "/v1/api-clients/" + id);
    }

    public JsonNode createClient(JsonNode req, String key) {
        String action = "businessApiClientsCreate";
        access.requireAdmin(action, "API_CLIENT", null);
        String name = Req.required(req, "name");
        String role = Req.text(req, "technical_role") == null ? "VIEWER" : Req.text(req, "technical_role");
        if (!TECHNICAL_ROLES.contains(role)) {
            throw ApiException.unprocessable("technical_role_not_allowed",
                    "An API client's technical user is a MAKER or a VIEWER (it never approves or administers)");
        }
        String auth = Req.text(req, "auth_method") == null ? "CLIENT_SECRET_BASIC" : Req.text(req, "auth_method");
        String jwks = Req.text(req, "jwks_uri");
        if (!AUTH_METHODS.contains(auth)) {
            throw ApiException.unprocessable("invalid_auth_method", "auth_method must be one of " + AUTH_METHODS);
        }
        if ("PRIVATE_KEY_JWT".equals(auth) && (jwks == null || !jwks.startsWith("https://"))) {
            throw ApiException.unprocessable("jwks_uri_required", "PRIVATE_KEY_JWT needs an https jwks_uri");
        }
        stepUp.require(action, "API_CLIENT", null);
        ObjectNode technical = technicalUser(name, role, key);
        UUID technicalId = UUID.fromString(technical.path("id").asString());
        ObjectNode body = down.json().createObjectNode()
                .put("name", name.length() > 120 ? name.substring(0, 120) : name)
                .put("description", Req.text(req, "description"))
                .put("client_type", "SERVER")
                .put("auth_method", auth)
                .put("jwks_uri", "PRIVATE_KEY_JWT".equals(auth) ? jwks : null)
                .put("environment", Req.text(req, "environment") == null ? "SANDBOX" : Req.text(req, "environment"))
                .put("owner_email", Req.text(req, "owner_email"))
                .put("fapi_profile", false)
                .put("business_customer_id", business().toString())
                .put("business_user_id", technicalId.toString());
        ArrayNode scopes = body.putArray("allowed_scopes");
        props.apiClientScopes().forEach(scopes::add);
        JsonNode created = down.post(IDENTITY, "/v1/api-clients", body, key, "create");
        String clientUuid = created.path("id").asString(null);
        rows.update(UserAdminService.USERS, technicalId, rows.object()
                .put("client_id", created.path("client_id").asString(null))
                .put("api_client_id", clientUuid));
        record("API_CLIENT", created, name);
        audit.succeeded(action, "API_CLIENT", null, clientUuid);
        return created;
    }

    /**
     * The TECHNICAL user of a client being created: its id is derived from the business, the admin and the client's
     * Idempotency-Key, so a retried creation reuses it (and identity-service replays the same client).
     */
    private ObjectNode technicalUser(String clientName, String role, String key) {
        UUID id = UUID.nameUUIDFromBytes(("bz-technical-user|" + Downstream.key(key, "api-client", "technical-user"))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return rows.find(UserAdminService.USERS, business(), id).orElseGet(() -> tx.execute(s -> {
            String display = "API client " + clientName;
            ObjectNode u = rows.object()
                    .put("id", id.toString())
                    .put("business_customer_id", business().toString())
                    .put("display_name", display.length() > 160 ? display.substring(0, 160) : display)
                    .put("role", role)
                    .put("status", "ACTIVE")
                    .put("user_type", "TECHNICAL");
            ObjectNode saved = rows.insert(UserAdminService.USERS, u);
            outbox.publish("channels.business_user.changed", UserAdminService.USERS, id,
                    rows.object().put("change", "ADDED").set("user", saved));
            return saved;
        }));
    }

    public JsonNode suspendClient(UUID id, JsonNode req, String key) {
        String action = "businessApiClientsSuspend";
        access.requireAdmin(action, "API_CLIENT", id);
        access.ownedObject("API_CLIENT", id, action);
        String reason = Req.required(req, "reason");
        JsonNode r = down.post(IDENTITY, "/v1/api-clients/" + id + "/suspend",
                down.json().createObjectNode().put("reason", reason), key, "suspend");
        // the client's machine-to-machine tokens stop working at once: its technical user is suspended too
        for (ObjectNode u : rows.list("select row_to_json(t)::text from business_user t where t.business_customer_id = ? "
                + "and t.api_client_id = ? and t.user_type = 'TECHNICAL' and t.status = 'ACTIVE'", business(), id)) {
            rows.update(UserAdminService.USERS, UUID.fromString(u.path("id").asString()), rows.object()
                    .put("status", "SUSPENDED").put("status_reason", reason.length() > 255 ? reason.substring(0, 255) : reason));
        }
        audit.succeeded(action, "API_CLIENT", id, null);
        return r;
    }

    public JsonNode keys(UUID id, Integer limit, String cursor) {
        access.requireAdmin("businessApiClientsKeys", "API_CLIENT", id);
        access.ownedObject("API_CLIENT", id, "businessApiClientsKeys");
        return down.page(down.get(IDENTITY, "/v1/api-clients/" + id + "/keys", Query.none().paging(limit, cursor)),
                r -> id.toString().equals(r.path("api_client_id").asString(null)));
    }

    public JsonNode createKey(UUID id, JsonNode req, String key) {
        String action = "businessApiClientsAddKeys";
        access.requireAdmin(action, "API_CLIENT", id);
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
        String action = "businessApiClientsKeysRevoke";
        access.requireAdmin(action, "API_CLIENT", id);
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
        access.requireAdmin("businessWebhooksList", "WEBHOOK_ENDPOINT", null);
        return down.page(objects("WEBHOOK_ENDPOINT", "/v1/webhook-endpoints/"));
    }

    public JsonNode endpoint(UUID id) {
        access.requireAdmin("businessWebhooksGet", "WEBHOOK_ENDPOINT", id);
        access.ownedObject("WEBHOOK_ENDPOINT", id, "businessWebhooksGet");
        return down.get(WEBHOOKS, "/v1/webhook-endpoints/" + id);
    }

    public JsonNode createEndpoint(JsonNode req, String key) {
        String action = "businessWebhooksCreate";
        access.requireAdmin(action, "WEBHOOK_ENDPOINT", null);
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
        String action = "businessWebhooksDelete";
        access.requireAdmin(action, "WEBHOOK_ENDPOINT", id);
        access.ownedObject("WEBHOOK_ENDPOINT", id, action);
        down.delete(WEBHOOKS, "/v1/webhook-endpoints/" + id);
        audit.succeeded(action, "WEBHOOK_ENDPOINT", id, null);
    }

    public JsonNode endpointState(UUID id, String op, JsonNode req, String key) {
        String action = op.equals("enable") ? "businessWebhooksEnable" : "businessWebhooksDisable";
        access.requireAdmin(action, "WEBHOOK_ENDPOINT", id);
        access.ownedObject("WEBHOOK_ENDPOINT", id, action);
        ObjectNode body = null;
        if (op.equals("disable")) {
            String reason = Req.text(req, "reason");
            body = down.json().createObjectNode().put("reason", reason == null ? "Disabled in the business portal" : reason);
        }
        JsonNode r = down.post(WEBHOOKS, "/v1/webhook-endpoints/" + id + "/" + op, body, key, op);
        audit.succeeded(action, "WEBHOOK_ENDPOINT", id, null);
        return r;
    }

    public JsonNode subscriptions(UUID id, Integer limit, String cursor) {
        access.requireAdmin("businessWebhooksSubscriptions", "WEBHOOK_ENDPOINT", id);
        access.ownedObject("WEBHOOK_ENDPOINT", id, "businessWebhooksSubscriptions");
        return down.page(down.get(WEBHOOKS, "/v1/webhook-subscriptions",
                        Query.of("webhook_endpoint_id", id).paging(limit, cursor)),
                r -> id.toString().equals(r.path("webhook_endpoint_id").asString(null)));
    }

    public JsonNode subscribe(UUID id, JsonNode req, String key) {
        String action = "businessWebhooksAddSubscriptions";
        access.requireAdmin(action, "WEBHOOK_ENDPOINT", id);
        access.ownedObject("WEBHOOK_ENDPOINT", id, action);
        String type = Req.required(req, "event_type");
        if (!allowedEventType(type, props.webhookEventTypes())) {
            throw ApiException.unprocessable("event_type_not_allowed", "Business endpoints may subscribe to "
                    + props.webhookEventTypes());
        }
        JsonNode created = down.post(WEBHOOKS, "/v1/webhook-subscriptions", down.json().createObjectNode()
                .put("webhook_endpoint_id", id.toString())
                .put("event_type", type)
                .put("filter_expression", "customer_id == \"" + business() + "\"")
                .put("is_active", true), key, "subscribe");
        audit.succeeded(action, "WEBHOOK_ENDPOINT", id, created.path("id").asString(null));
        return created;
    }

    public JsonNode deliveries(UUID id, Integer limit, String cursor) {
        access.requireAdmin("businessWebhooksDeliveries", "WEBHOOK_ENDPOINT", id);
        access.ownedObject("WEBHOOK_ENDPOINT", id, "businessWebhooksDeliveries");
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
        for (ObjectNode o : rows.list("select row_to_json(t)::text from business_object t where t.business_customer_id = ? "
                + "and t.object_type = ? order by t.id desc limit 200", business(), type)) {
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
        if (rows.one("select row_to_json(t)::text from business_object t where t.object_id = ?", UUID.fromString(id))
                .isPresent()) {
            return;       // an idempotent replay of the same creation
        }
        rows.insert(OBJECTS, rows.object().put("business_customer_id", business().toString()).put("object_type", type)
                .put("object_id", id).put("label", label.length() > 160 ? label.substring(0, 160) : label));
    }
}
