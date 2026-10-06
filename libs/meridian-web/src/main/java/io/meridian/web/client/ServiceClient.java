package io.meridian.web.client;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calls another Meridian service by name, propagating the caller's tenant, user and bearer token.
 * Base URLs come from {@code meridian.services.<name>.url}, else {@code meridian.services.url-template} ({@code {name}},
 * {@code {port}} placeholders), else http://localhost:<port> from the generated services manifest. Downstream problem responses are re-thrown as {@link ApiException} with the same code;
 * 5xx / connection failures become 502 {@code upstream_error}.
 */
public class ServiceClient {
    private final JsonMapper json;
    private final Environment env;
    private final Map<String, Integer> ports = new HashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Duration timeout;
    private final ServiceTokenProvider serviceTokens;

    public ServiceClient(JsonMapper json, Environment env) {
        this(json, env, new ServiceTokenProvider(json, env));
    }

    public ServiceClient(JsonMapper json, Environment env, ServiceTokenProvider serviceTokens) {
        this.json = json;
        this.env = env;
        this.serviceTokens = serviceTokens;
        this.timeout = Duration.ofMillis(env.getProperty("meridian.services.timeout-ms", Long.class, 10_000L));
        ClassPathResource manifest = new ClassPathResource("meridian/services.json");
        if (manifest.exists()) {
            try (InputStream in = manifest.getInputStream()) {
                json.readTree(in).forEach(s -> ports.put(s.get("name").asString(), s.get("port").asInt()));
            } catch (IOException e) {
                throw new IllegalStateException("cannot read services manifest", e);
            }
        }
    }

    public JsonNode get(String service, String path) {
        return send(service, "GET", path, null, null);
    }

    public JsonNode post(String service, String path, Object body, String idempotencyKey) {
        return send(service, "POST", path, body, idempotencyKey);
    }

    public JsonNode patch(String service, String path, Object body, String ifMatch) {
        return send(service, "PATCH", path, body, ifMatch == null ? null : "if-match:" + ifMatch);
    }

    public JsonNode send(String service, String method, String path, Object body, String key) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl(service) + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
        TenantContext.current().ifPresent(t -> b.header("X-Tenant-Id", t.toString()));
        b.header("X-User", TenantContext.principal());
        String auth = currentAuthorization();
        if (auth != null) {
            b.header("Authorization", auth);                         // act on behalf of the calling user
        } else if (serviceTokens.configured()) {
            b.header("Authorization", "Bearer " + serviceTokens.token()); // job/callback: service identity
        }
        if (key != null) {
            if (key.startsWith("if-match:")) {
                b.header("If-Match", key.substring(9));
            } else {
                b.header("Idempotency-Key", key);
            }
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        HttpResponse<String> r;
        try {
            r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ApiException(502, "upstream_error", service + " is not reachable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(502, "upstream_error", "call to " + service + " interrupted");
        }
        JsonNode node = r.body() == null || r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
        if (r.statusCode() >= 500) {
            throw new ApiException(502, "upstream_error", service + " failed: " + node.path("detail").asString());
        }
        if (r.statusCode() >= 400) {
            throw new ApiException(r.statusCode(), node.path("code").asString("upstream_rejected"),
                    service + ": " + node.path("detail").asString());
        }
        return node;
    }

    String baseUrl(String service) {
        String configured = env.getProperty("meridian.services." + service + ".url");
        if (configured != null) {
            return configured;
        }
        // Cluster deployments: e.g. http://{name}.meridian.svc.cluster.local (Kubernetes service per Meridian service)
        String template = env.getProperty("meridian.services.url-template");
        if (template != null && !template.isBlank()) {
            if (!ports.containsKey(service)) {
                throw new IllegalArgumentException("unknown service " + service);
            }
            return template.replace("{name}", service).replace("{port}", String.valueOf(ports.get(service)));
        }
        Integer port = ports.get(service);
        if (port == null) {
            throw new IllegalArgumentException("unknown service " + service);
        }
        return "http://localhost:" + port;
    }

    private static String currentAuthorization() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) {
            return a.getRequest().getHeader("Authorization");
        }
        return null;
    }
}
