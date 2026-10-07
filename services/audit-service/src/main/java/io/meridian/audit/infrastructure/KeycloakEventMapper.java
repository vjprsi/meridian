package io.meridian.audit.infrastructure;

import io.meridian.audit.application.AuditRecorder.NewAuditEvent;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * GAP-06: maps Keycloak user events (sign-ins, sign-in failures, factor changes, lockouts, client-credential logins)
 * and admin events (realm / client / user changes with their representation) to audit events.
 *
 * <ul>
 *   <li>Actions: {@code idp.<event type>} ({@code idp.login}, {@code idp.login_error}, {@code idp.client_login}, ...) and
 *       {@code idp.admin.<operation>.<resource type>} ({@code idp.admin.create.user}).</li>
 *   <li>Actors: the Keycloak user id (pseudonymous), or the client for client-credential logins; actor type from the
 *       client (console / developer portal: STAFF_USER, customer web / business portal / merchant app: CUSTOMER, agent app:
 *       AGENT, svc-* and Meridian clients: SERVICE, other confidential clients: API_CLIENT).</li>
 *   <li>Never stored: credentials, secrets, tokens, passwords and the username (a login name may be an e-mail address);
 *       keys are converted to snake_case so the audit PII masker pseudonymises first_name / last_name / email.</li>
 *   <li>Source event ids {@code kc:<id>} / {@code kc-admin:<id>}: polling the same window twice records nothing twice.</li>
 * </ul>
 */
public final class KeycloakEventMapper {
    public static final String SERVICE = "keycloak";
    private static final Set<String> DROPPED = Set.of("credentials", "secret", "password", "value", "secret_data",
            "client_secret", "secret_value", "token", "access_token", "refresh_token", "id_token", "private_key",
            "username", "code_id", "token_id", "registration_access_token");
    private static final Map<String, String> ACTOR_TYPES = Map.of(
            "meridian-console", "STAFF_USER", "meridian-developer-portal", "STAFF_USER",
            "meridian-customer-web", "CUSTOMER", "meridian-business-portal", "CUSTOMER", "meridian-merchant-app", "CUSTOMER",
            "meridian-agent-app", "AGENT", "meridian-customer-mobile", "CUSTOMER", "meridian-agent-mobile", "AGENT");

    private final JsonMapper json;

    public KeycloakEventMapper(JsonMapper json) {
        this.json = json;
    }

    /** A user event of {@code GET /admin/realms/{realm}/events}. */
    public NewAuditEvent userEvent(JsonNode e) {
        String type = e.path("type").asString("UNKNOWN");
        String client = text(e, "clientId");
        String user = text(e, "userId");
        boolean clientLogin = type.startsWith("CLIENT_LOGIN");
        String actorType = clientLogin ? clientActor(client) : ACTOR_TYPES.getOrDefault(client, user == null ? "SYSTEM"
                : "STAFF_USER");
        String actor = clientLogin ? client : user;
        ObjectNode state = json.createObjectNode();
        if (client != null) {
            state.put("client_id", client);
        }
        if (e.has("details") && e.get("details").isObject()) {
            state.set("details", sanitize(e.get("details")));
        }
        String error = text(e, "error");
        return new NewAuditEvent(Instant.ofEpochMilli(e.path("time").asLong()), actorType,
                cut(actor == null ? "unknown" : actor, 64), null, null, cut("idp." + type.toLowerCase(Locale.ROOT), 128),
                "idp_event", cut(firstNonNull(text(e, "sessionId"), user), 64),
                error == null && !type.endsWith("_ERROR") ? "SUCCESS" : "FAILURE", cut(error, 500), null, state,
                null, null, cut(text(e, "ipAddress"), 64), null, cut(text(e, "sessionId"), 64), null, null, SERVICE,
                cut("kc:" + e.path("id").asString(""), 64));
    }

    /** An admin event of {@code GET /admin/realms/{realm}/admin-events} (with representation). */
    public NewAuditEvent adminEvent(JsonNode e) {
        String operation = e.path("operationType").asString("ACTION").toLowerCase(Locale.ROOT);
        String resource = e.path("resourceType").asString("UNKNOWN").toLowerCase(Locale.ROOT);
        JsonNode auth = e.path("authDetails");
        ObjectNode state = json.createObjectNode();
        state.put("resource_path", e.path("resourcePath").asString(""));
        String representation = text(e, "representation");
        if (representation != null && !representation.isBlank()) {
            try {
                state.set("representation", sanitize(json.readTree(representation)));
            } catch (RuntimeException ex) {
                state.put("representation_unparsed", true);       // never store text we cannot sanitise
            }
        }
        String error = text(e, "error");
        return new NewAuditEvent(Instant.ofEpochMilli(e.path("time").asLong()), "STAFF_USER",
                cut(firstNonNull(text(auth, "userId"), text(auth, "clientId"), "unknown"), 64), null, null,
                cut("idp.admin." + operation + "." + resource, 128), cut("idp_" + resource, 64),
                cut(e.path("resourcePath").asString(null), 64), error == null ? "SUCCESS" : "FAILURE", cut(error, 500),
                null, state, null, null, cut(text(auth, "ipAddress"), 64), null, null, null, null, SERVICE,
                cut("kc-admin:" + e.path("id").asString(""), 64));
    }

    /** Drops secrets and login names, converts keys to snake_case (recursively). */
    JsonNode sanitize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = json.createObjectNode();
            for (Map.Entry<String, JsonNode> f : node.properties()) {
                String key = snake(f.getKey());
                if (!DROPPED.contains(key)) {
                    out.set(key, sanitize(f.getValue()));
                }
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = json.createArrayNode();
            node.forEach(n -> out.add(sanitize(n)));
            return out;
        }
        return node;
    }

    static String snake(String key) {
        return key.replaceAll("([a-z0-9])([A-Z])", "$1_$2").replace('-', '_').toLowerCase(Locale.ROOT);
    }

    private static String clientActor(String client) {
        return client != null && (client.startsWith("svc-") || client.startsWith("meridian-")) ? "SERVICE" : "API_CLIENT";
    }

    private static String text(JsonNode n, String f) {
        return n != null && n.hasNonNull(f) ? n.get(f).asString() : null;
    }

    private static String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
