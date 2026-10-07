package io.meridian.customerchannel.application;

import io.meridian.customerchannel.config.ChannelProperties;
import io.meridian.customerchannel.config.ChannelProperties.IdentityProvider;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The customer's sessions and sign-in factors at the identity provider (Keycloak admin REST API), through the
 * confidential client {@code meridian-customer-sessions} (realm-management view-users and manage-users). Every call is
 * for the {@code sub} of the signed-in customer's own token; ids are validated before they go into a URL.
 *
 * <p>Failures never fail the customer's request: reads return empty, ends return false (the channel's own revocation
 * still applies), and the error is logged.
 */
@Component
public class IdentityProviderSessions {
    private static final Logger log = LoggerFactory.getLogger(IdentityProviderSessions.class);
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    /** A Keycloak user session. */
    public record IdpSession(String id, String ipAddress, Instant start, Instant lastAccess, List<String> clients) {}

    /** A sign-in credential (second factors only: otp, webauthn, webauthn-passwordless). */
    public record Factor(String id, String type, String label, Instant createdAt) {}

    private final ChannelProperties props;
    private final JsonMapper json;
    private final HttpClient http;
    private volatile String token;
    private volatile Instant tokenExpires = Instant.EPOCH;

    public IdentityProviderSessions(ChannelProperties props, JsonMapper json) {
        this.props = props;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
    }

    public boolean configured() {
        return props.sessions().idpConfigured();
    }

    /** The user's sessions; empty when not configured or unavailable. */
    public Optional<List<IdpSession>> sessions(String subject) {
        if (!valid(subject)) {
            return Optional.empty();
        }
        return call("GET", "/users/" + subject + "/sessions").map(body -> {
            List<IdpSession> out = new ArrayList<>();
            for (JsonNode s : body) {
                List<String> clients = new ArrayList<>();
                s.path("clients").forEach(c -> clients.add(c.asString()));
                out.add(new IdpSession(s.path("id").asString(), s.path("ipAddress").asString(null),
                        millis(s.path("start")), millis(s.path("lastAccess")), clients));
            }
            return out;
        });
    }

    /** The user's second factors; empty when not configured or unavailable. */
    public Optional<List<Factor>> factors(String subject) {
        if (!valid(subject)) {
            return Optional.empty();
        }
        return call("GET", "/users/" + subject + "/credentials").map(body -> {
            List<Factor> out = new ArrayList<>();
            for (JsonNode c : body) {
                String type = c.path("type").asString("");
                if (type.equals("otp") || type.startsWith("webauthn")) {
                    out.add(new Factor(c.path("id").asString(), type, c.path("userLabel").asString(null),
                            millis(c.path("createdDate"))));
                }
            }
            return out;
        });
    }

    /** Ends one session (its refresh tokens and SSO cookie stop working). True when ended or already gone. */
    public boolean endSession(String sessionId) {
        return valid(sessionId) && call("DELETE", "/sessions/" + sessionId).isPresent();
    }

    /** Ends every session of the user. */
    public boolean endAll(String subject) {
        return valid(subject) && call("POST", "/users/" + subject + "/logout").isPresent();
    }

    private static boolean valid(String id) {
        return id != null && ID.matcher(id).matches();
    }

    private Optional<JsonNode> call(String method, String path) {
        if (!configured()) {
            return Optional.empty();
        }
        IdentityProvider idp = props.sessions().identityProvider();
        try {
            HttpRequest req = HttpRequest.newBuilder(admin(idp, path)).timeout(idp.timeout())
                    .header("Authorization", "Bearer " + token(idp)).header("Accept", "application/json")
                    .method(method, HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 401) {
                tokenExpires = Instant.EPOCH;           // token revoked or rotated: fetch a new one next time
            }
            if (res.statusCode() == 404 && !"GET".equals(method)) {
                return Optional.of(json.createObjectNode());   // already ended
            }
            if (res.statusCode() / 100 != 2) {
                log.warn("identity provider {} {} answered {}", method, path.replaceAll("/[^/]+/", "/{id}/"),
                        res.statusCode());
                return Optional.empty();
            }
            return Optional.of(res.body() == null || res.body().isBlank() ? json.createObjectNode()
                    : json.readTree(res.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("identity provider {} call failed: {}", method, e.toString());
            return Optional.empty();
        }
    }

    private static URI admin(IdentityProvider idp, String path) {
        String base = idp.baseUrl().toString().replaceAll("/+$", "");
        return URI.create(base + "/admin/realms/" + enc(idp.realm()) + path);
    }

    private synchronized String token(IdentityProvider idp) throws Exception {
        if (token != null && Instant.now().isBefore(tokenExpires)) {
            return token;
        }
        String base = idp.baseUrl().toString().replaceAll("/+$", "");
        String form = "grant_type=client_credentials&client_id=" + enc(idp.clientId()) + "&client_secret="
                + enc(idp.clientSecret());
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/realms/" + enc(idp.realm())
                        + "/protocol/openid-connect/token")).timeout(idp.timeout())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IllegalStateException("token endpoint answered " + res.statusCode());
        }
        JsonNode body = json.readTree(res.body());
        token = body.path("access_token").asString();
        tokenExpires = Instant.now().plusSeconds(Math.max(10, body.path("expires_in").asLong(60) - 30));
        return token;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static Instant millis(JsonNode n) {
        return n.isNumber() ? Instant.ofEpochMilli(n.asLong()) : null;
    }
}
