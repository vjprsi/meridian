package io.meridian.router;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the tenant registry in tenant-service with the router's service identity: {@code GET
 * /v1/platform/tenants/{id}/routing} and {@code GET /v1/platform/cells}. Both are platform-operator operations
 * (SEC-15), so the router sends an internal client-credentials token and the operator tenant id; it never forwards a
 * caller's token or tenant to the registry.
 */
@Component
public class RegistryClient {
    private final RouterProperties props;
    private final JsonMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .version(HttpClient.Version.HTTP_1_1).build();
    private volatile String token;
    private volatile Instant tokenExpires = Instant.EPOCH;

    public RegistryClient(RouterProperties props, JsonMapper json) {
        this.props = props;
        this.json = json;
    }

    /** The tenant's routing; empty when the registry does not know the tenant (404). */
    public Optional<Registry.TenantRouting> routing(UUID tenant, java.util.function.Consumer<Registry.Cell> cells) {
        HttpResponse<String> r = get("/v1/platform/tenants/" + tenant + "/routing");
        if (r.statusCode() == 404) {
            return Optional.empty();
        }
        if (r.statusCode() != 200) {
            throw new Registry.UnavailableException("tenant registry answered " + r.statusCode());
        }
        JsonNode body = json.readTree(r.body());
        for (String role : new String[] {"primary", "standby"}) {
            JsonNode c = body.get(role);
            if (c != null && c.hasNonNull("id")) {
                cells.accept(Registry.Cell.of(c));
            }
        }
        return Optional.of(Registry.TenantRouting.of(body));
    }

    /** Every cell (paged). */
    public List<Registry.Cell> cells() {
        List<Registry.Cell> out = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 50; page++) {
            HttpResponse<String> r = get("/v1/platform/cells?limit=200"
                    + (cursor == null ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8)));
            if (r.statusCode() != 200) {
                throw new Registry.UnavailableException("tenant registry answered " + r.statusCode() + " listing cells");
            }
            JsonNode body = json.readTree(r.body());
            body.path("data").forEach(c -> out.add(Registry.Cell.of(c)));
            cursor = Registry.text(body, "next_cursor");
            if (cursor == null || !body.path("has_more").asBoolean(false)) {
                break;
            }
        }
        return out;
    }

    private HttpResponse<String> get(String path) {
        if (props.registryUrl() == null || props.registryUrl().isBlank()) {
            throw new Registry.UnavailableException("meridian.router.registry-url is not set");
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(props.registryUrl() + path))
                .timeout(Duration.ofSeconds(3)).header("Accept", "application/json")
                .header("X-Meridian-Service", "cell-router");
        if (props.operatorTenant() != null && !props.operatorTenant().isBlank()) {
            b.header("X-Tenant-Id", props.operatorTenant());
        }
        String bearer = serviceToken();
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        try {
            return http.send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new Registry.UnavailableException("tenant registry unreachable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Registry.UnavailableException("interrupted");
        }
    }

    /** Client-credentials token of the router (cached until shortly before it expires); null without a token URI. */
    private String serviceToken() {
        RouterProperties.Auth a = props.auth();
        if (a.tokenUri() == null || a.tokenUri().isBlank()) {
            return null;
        }
        if (token != null && Instant.now().isBefore(tokenExpires)) {
            return token;
        }
        synchronized (this) {
            if (token != null && Instant.now().isBefore(tokenExpires)) {
                return token;
            }
            String form = "grant_type=client_credentials&client_id=" + enc(a.clientId()) + "&client_secret="
                    + enc(a.clientSecret()) + (a.scope() == null || a.scope().isBlank() ? "" : "&scope=" + enc(a.scope()));
            try {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(a.tokenUri()))
                        .timeout(Duration.ofSeconds(3)).header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) {
                    throw new Registry.UnavailableException("token endpoint answered " + r.statusCode());
                }
                JsonNode t = json.readTree(r.body());
                token = t.get("access_token").asString();
                tokenExpires = Instant.now().plusSeconds(Math.max(10, t.path("expires_in").asLong(60) - 30));
                return token;
            } catch (IOException e) {
                throw new Registry.UnavailableException("token endpoint unreachable: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Registry.UnavailableException("interrupted");
            }
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
