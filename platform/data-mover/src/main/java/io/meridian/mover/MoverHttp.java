package io.meridian.mover;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calls Meridian services as the mover's own identity: client-credentials token of {@code svc-data-mover} (GAP-16: the
 * token's client proves the calling service, so the services' data-lifecycle move API admits it) plus
 * {@code X-Meridian-Service: data-mover} (dev mode) and the tenant in {@code X-Tenant-Id}.
 */
public class MoverHttp {
    public static final String SERVICE = "data-mover";

    /** A failure worth retrying later (network, 5xx, 429, freeze grace). */
    public static class TransientException extends RuntimeException {
        public TransientException(String m) {
            super(m);
        }
    }

    /** A failure that needs an operator (4xx other than 429, verification mismatch, schema drift). */
    public static class PermanentException extends RuntimeException {
        public PermanentException(String m) {
            super(m);
        }
    }

    public record Response(int status, JsonNode body) {
        public String code() {
            return body == null ? "" : body.path("code").asString("");
        }
    }

    private final JsonMapper json;
    private final MoverProperties.Auth auth;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1).build();
    private volatile String token;
    private volatile Instant tokenExpires = Instant.EPOCH;

    public MoverHttp(JsonMapper json, MoverProperties.Auth auth) {
        this.json = json;
        this.auth = auth;
    }

    public JsonMapper json() {
        return json;
    }

    /** POST, returning the response whatever its status; network failures are transient. */
    public Response post(String url, Object body, UUID tenant, String idempotencyKey) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .header("X-Meridian-Service", SERVICE).header("X-User", SERVICE)
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : json.writeValueAsString(body)));
        if (tenant != null) {
            b.header("X-Tenant-Id", tenant.toString());
        }
        if (idempotencyKey != null) {
            b.header("Idempotency-Key", idempotencyKey);
        }
        String bearer = token();
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        try {
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode node = r.body() == null || r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
            return new Response(r.statusCode(), node);
        } catch (IOException e) {
            throw new TransientException(url + " unreachable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransientException("interrupted");
        } catch (RuntimeException e) {
            throw new TransientException(url + " answered unreadable JSON: " + e.getMessage());
        }
    }

    /** POST that must succeed: 5xx / 429 transient, other 4xx permanent. */
    public JsonNode postOk(String url, Object body, UUID tenant, String idempotencyKey) {
        Response r = post(url, body, tenant, idempotencyKey);
        if (r.status() >= 200 && r.status() < 300) {
            return r.body();
        }
        String msg = url + " answered " + r.status() + " " + r.code() + ": " + r.body().path("detail").asString("");
        if (r.status() >= 500 || r.status() == 429) {
            throw new TransientException(msg);
        }
        throw new PermanentException(msg);
    }

    private String token() {
        if (auth.tokenUri() == null || auth.tokenUri().isBlank()) {
            return null;
        }
        if (token != null && Instant.now().isBefore(tokenExpires)) {
            return token;
        }
        synchronized (this) {
            if (token != null && Instant.now().isBefore(tokenExpires)) {
                return token;
            }
            String form = "grant_type=client_credentials&client_id=" + enc(auth.clientId()) + "&client_secret="
                    + enc(auth.clientSecret()) + (auth.scope() == null || auth.scope().isBlank() ? "" : "&scope=" + enc(auth.scope()));
            try {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(auth.tokenUri()))
                        .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) {
                    throw new TransientException("token endpoint answered " + r.statusCode());
                }
                JsonNode t = json.readTree(r.body());
                token = t.get("access_token").asString();
                tokenExpires = Instant.now().plusSeconds(Math.max(10, t.path("expires_in").asLong(60) - 30));
                return token;
            } catch (IOException e) {
                throw new TransientException("token endpoint unreachable: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TransientException("interrupted");
            }
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
