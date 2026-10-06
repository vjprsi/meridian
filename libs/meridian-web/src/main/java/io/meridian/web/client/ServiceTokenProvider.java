package io.meridian.web.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * OAuth2 client-credentials token for service-to-service calls that have no user token to forward (scheduled
 * jobs, provider callbacks). Configured with {@code meridian.service-auth.token-uri}, {@code .client-id},
 * {@code .client-secret}; the token must carry scope {@code meridian:internal}, which lets the receiving service
 * accept the tenant from the {@code X-Tenant-Id} header. Tokens are cached until 30 s before expiry.
 */
public class ServiceTokenProvider {
    private record Token(String value, Instant expiresAt) {}

    private final JsonMapper json;
    private final String tokenUri;
    private final String clientId;
    private final String clientSecret;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final AtomicReference<Token> cached = new AtomicReference<>();

    public ServiceTokenProvider(JsonMapper json, Environment env) {
        this.json = json;
        this.tokenUri = env.getProperty("meridian.service-auth.token-uri");
        this.clientId = env.getProperty("meridian.service-auth.client-id");
        this.clientSecret = env.getProperty("meridian.service-auth.client-secret");
    }

    public boolean configured() {
        return tokenUri != null && clientId != null && clientSecret != null;
    }

    /** A valid access token, fetching a new one when the cached token is about to expire. */
    public String token() {
        Token t = cached.get();
        if (t != null && Instant.now().isBefore(t.expiresAt().minusSeconds(30))) {
            return t.value();
        }
        synchronized (this) {
            t = cached.get();
            if (t != null && Instant.now().isBefore(t.expiresAt().minusSeconds(30))) {
                return t.value();
            }
            Token fresh = fetch();
            cached.set(fresh);
            return fresh.value();
        }
    }

    private Token fetch() {
        String basic = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(URI.create(tokenUri))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials&scope="
                        + URLEncoder.encode("meridian:internal", StandardCharsets.UTF_8)))
                .build();
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                throw new IllegalStateException("token endpoint returned " + r.statusCode());
            }
            JsonNode body = json.readTree(r.body());
            return new Token(body.get("access_token").asString(),
                    Instant.now().plusSeconds(body.path("expires_in").asLong(60)));
        } catch (IOException e) {
            throw new IllegalStateException("cannot obtain service token: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted obtaining service token", e);
        }
    }
}
