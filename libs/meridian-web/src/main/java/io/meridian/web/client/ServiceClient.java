package io.meridian.web.client;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.security.CustomerContext;
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
 * Calls another Meridian service by name, propagating the caller's tenant, user and bearer token. A customer's token
 * ({@link CustomerContext}) is never forwarded: those calls use the service identity (client credentials) instead.
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
        HttpRequest.Builder b = request(service, method, path, body, key, "application/json");
        HttpResponse<String> r = exchange(service, b, HttpResponse.BodyHandlers.ofString());
        JsonNode node = r.body() == null || r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
        if (r.statusCode() >= 400) {
            throw problem(service, r.statusCode(), node);
        }
        return node;
    }

    /** A file returned by another service (PDF receipt, statement, text letter). */
    public record Download(byte[] content, String contentType, String contentDisposition) {}

    /**
     * GET of a non-JSON response (file download), with the same identity, tenant and error mapping as
     * {@link #get(String, String)}.
     */
    public Download getBytes(String service, String path) {
        HttpRequest.Builder b = request(service, "GET", path, null, null, "*/*");
        HttpResponse<byte[]> r = exchange(service, b, HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() >= 400) {
            JsonNode node;
            try {
                node = json.readTree(r.body());
            } catch (RuntimeException e) {
                node = json.createObjectNode();
            }
            throw problem(service, r.statusCode(), node == null ? json.createObjectNode() : node);
        }
        return new Download(r.body(), r.headers().firstValue("Content-Type").orElse("application/octet-stream"),
                r.headers().firstValue("Content-Disposition").orElse(null));
    }

    private HttpRequest.Builder request(String service, String method, String path, Object body, String key,
                                        String accept) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl(service) + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", accept);
        TenantContext.current().ifPresent(t -> b.header("X-Tenant-Id", t.toString()));
        b.header("X-User", TenantContext.principal());
        boolean customer = CustomerContext.current().isPresent();
        String auth = currentAuthorization();
        if (customer) {
            // the customer the service acts for; receivers honour it only with an internal (service) token
            b.header(CustomerContext.ON_BEHALF_HEADER, CustomerContext.customerId().toString());
            // A customer's token is never forwarded (it carries no domain scope, and the receiving service must not
            // act with the customer's identity): the customer channel calls with its own service identity for the
            // tenant, as the acting user customer:<id>. Dev-mode role / MFA headers are not relayed either.
            if (serviceTokens.configured()) {
                b.header("Authorization", "Bearer " + serviceTokens.token());
            } else if (auth != null) {
                throw new ApiException(503, "service_identity_not_configured",
                        "Customer requests need meridian.service-auth.* (client credentials) to call " + service);
            }
        } else {
            // dev-mode caller attributes (roles, step-up MFA): receivers only honour them in dev mode; with JWTs the
            // forwarded token carries them
            forwardInbound(b, "X-Roles");
            forwardInbound(b, "X-MFA-Verified");
            if (auth != null) {
                b.header("Authorization", auth);                         // act on behalf of the calling user
            } else if (serviceTokens.configured()) {
                b.header("Authorization", "Bearer " + serviceTokens.token()); // job/callback: service identity
            }
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
        return b;
    }

    private <T> HttpResponse<T> exchange(String service, HttpRequest.Builder b, HttpResponse.BodyHandler<T> handler) {
        try {
            return http.send(b.build(), handler);
        } catch (IOException e) {
            throw new ApiException(502, "upstream_error", service + " is not reachable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(502, "upstream_error", "call to " + service + " interrupted");
        }
    }

    private static ApiException problem(String service, int status, JsonNode node) {
        if (status >= 500) {
            return new ApiException(502, "upstream_error", service + " failed: " + node.path("detail").asString());
        }
        return new ApiException(status, node.path("code").asString("upstream_rejected"),
                service + ": " + node.path("detail").asString());
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

    private static void forwardInbound(HttpRequest.Builder b, String header) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) {
            String v = a.getRequest().getHeader(header);
            if (v != null && !v.isBlank()) {
                b.header(header, v);
            }
        }
    }

    private static String currentAuthorization() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) {
            return a.getRequest().getHeader("Authorization");
        }
        return null;
    }
}
