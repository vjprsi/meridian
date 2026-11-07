package io.meridian.gateway;

import io.meridian.common.id.Ids;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Streams each /v1 request to the owning service and the response back (virtual threads, no buffering of bodies).
 * Adds X-Request-Id and X-Forwarded-* headers; maps unknown routes and unreachable services to problem+json.
 * Internal identity headers are dropped outside dev mode and the client address is taken from the socket or a trusted
 * proxy ({@link HeaderPolicy}; SEC-13 / SEC-16); request bodies are capped ({@code meridian.gateway.max-body-size},
 * per service overrides in {@code meridian.gateway.service-max-body-size}; SEC-26).
 */
@RestController
public class ProxyController {
    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);
    private static final Set<String> HOP_BY_HOP = HeaderPolicy.HOP_BY_HOP;

    /**
     * Upstream of a service: {@code meridian.gateway.services.<name>} override, else {@code url-template} with
     * {@code {name}} / {@code {port}} placeholders (e.g. {@code http://{name}} in Kubernetes), else
     * http://localhost:<port>.
     */
    @ConfigurationProperties(prefix = "meridian.gateway")
    public record GatewayProperties(Map<String, String> services, Duration timeout, String urlTemplate,
                                    java.util.List<String> trustedProxies, DataSize maxBodySize,
                                    Map<String, DataSize> serviceMaxBodySize) {
        public GatewayProperties {
            services = services == null ? Map.of() : services;
            timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
            trustedProxies = trustedProxies == null ? java.util.List.of() : trustedProxies;
            maxBodySize = maxBodySize == null ? DataSize.ofMegabytes(16) : maxBodySize;
            serviceMaxBodySize = serviceMaxBodySize == null ? Map.of() : serviceMaxBodySize;
        }

        long maxBodyBytes(String service) {
            return serviceMaxBodySize.getOrDefault(service, maxBodySize).toBytes();
        }
    }

    private final RouteTable routes;
    private final GatewayProperties props;
    private final HeaderPolicy headers;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public ProxyController(RouteTable routes, GatewayProperties props,
                           @Value("${meridian.security-mode:jwt}") String securityMode) {
        this.routes = routes;
        this.props = props;
        this.headers = new HeaderPolicy(securityMode, props.trustedProxies());
        log.info("Edge gateway loaded {} routes", routes.size());
        if (headers.devMode()) {
            log.warn("Edge gateway in dev security mode: X-Tenant-Id / X-User / X-Roles are forwarded. Local only.");
        }
    }

    @GetMapping(value = "/openapi.yaml", produces = "application/yaml")
    public byte[] openApi() throws IOException {
        try (InputStream in = new ClassPathResource("meridian/meridian.openapi.yaml").getInputStream()) {
            return in.readAllBytes();
        }
    }

    @RequestMapping("/v1/**")
    public void proxy(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String path = req.getRequestURI();
        String requestId = req.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank()) {
            requestId = "req_" + Ids.newId();
        }
        res.setHeader("X-Request-Id", requestId);
        var route = routes.match(req.getMethod(), path);
        if (route.isEmpty()) {
            boolean known = routes.pathKnown(path);
            problem(res, known ? 405 : 404, known ? "method_not_allowed" : "not_found",
                    known ? "Method " + req.getMethod() + " is not allowed on " + path : "No API at " + path, requestId);
            return;
        }
        RouteTable.Route r = route.get();
        String base = props.services().get(r.service());
        if (base == null) {
            base = props.urlTemplate() == null || props.urlTemplate().isBlank() ? "http://localhost:" + r.port()
                    : props.urlTemplate().replace("{name}", r.service()).replace("{port}", String.valueOf(r.port()));
        }
        String target = base + path + (req.getQueryString() == null ? "" : "?" + req.getQueryString());

        long maxBody = props.maxBodyBytes(r.service());
        if (req.getContentLengthLong() > maxBody) {
            problem(res, 413, "payload_too_large", "Request body exceeds " + maxBody + " bytes", requestId);
            return;
        }

        URI targetUri;
        try {
            targetUri = URI.create(target);
        } catch (IllegalArgumentException e) {
            problem(res, 400, "malformed_request", "The request URL or query string is malformed", requestId);
            return;
        }
        HttpRequest.Builder out = HttpRequest.newBuilder(targetUri).timeout(props.timeout());
        for (String name : Collections.list(req.getHeaderNames())) {
            if (headers.forward(name)) {
                for (String v : Collections.list(req.getHeaders(name))) {
                    out.header(name, v);
                }
            }
        }
        out.setHeader("X-Request-Id", requestId);
        String peer = req.getRemoteAddr();
        boolean viaProxy = headers.trusted(peer);
        out.header("X-Forwarded-For", headers.clientAddress(peer, Collections.list(req.getHeaders("X-Forwarded-For"))));
        String proto = viaProxy ? req.getHeader("X-Forwarded-Proto") : null;
        out.header("X-Forwarded-Proto", proto == null || proto.isBlank() ? req.getScheme() : proto.split(",")[0].trim());
        String host = viaProxy ? req.getHeader("X-Forwarded-Host") : null;
        out.header("X-Forwarded-Host", host == null || host.isBlank() ? String.valueOf(req.getHeader("Host"))
                : host.split(",")[0].trim());
        boolean hasBody = req.getContentLengthLong() > 0 || req.getHeader("Transfer-Encoding") != null;
        LimitedInputStream[] sent = new LimitedInputStream[1];
        out.method(req.getMethod(), hasBody
                ? HttpRequest.BodyPublishers.ofInputStream(() -> {
                    try {
                        sent[0] = new LimitedInputStream(req.getInputStream(), maxBody);
                        return sent[0];
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                })
                : HttpRequest.BodyPublishers.noBody());
        try {
            HttpResponse<InputStream> upstream = client.send(out.build(), HttpResponse.BodyHandlers.ofInputStream());
            res.setStatus(upstream.statusCode());
            upstream.headers().map().forEach((name, values) -> {
                String lower = name.toLowerCase(Locale.ROOT);
                if (!HOP_BY_HOP.contains(lower) && !lower.equals("x-request-id") && !name.startsWith(":")) {
                    values.forEach(v -> res.addHeader(name, v));
                }
            });
            res.setHeader("X-Meridian-Service", r.service());
            try (InputStream body = upstream.body()) {
                body.transferTo(res.getOutputStream());
            }
        } catch (HttpTimeoutException e) {
            problem(res, 504, "upstream_timeout", r.service() + " did not answer in time", requestId);
        } catch (IOException e) {
            if (sent[0] != null && sent[0].exceeded()) {
                problem(res, 413, "payload_too_large", "Request body exceeds " + maxBody + " bytes", requestId);
                return;
            }
            log.warn("Upstream {} unreachable at {}: {}", r.service(), base, e.toString());
            problem(res, 503, "service_unavailable", r.service() + " is not reachable", requestId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            problem(res, 503, "service_unavailable", "request interrupted", requestId);
        }
    }

    /** Counts body bytes streamed upstream and fails once the cap is passed (chunked uploads without a length). */
    static final class LimitedInputStream extends java.io.FilterInputStream {
        private final long max;
        private long count;
        private boolean exceeded;

        LimitedInputStream(InputStream in, long max) {
            super(in);
            this.max = max;
        }

        boolean exceeded() {
            return exceeded;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                add(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = super.read(buf, off, len);
            if (n > 0) {
                add(n);
            }
            return n;
        }

        private void add(long n) throws IOException {
            count += n;
            if (count > max) {
                exceeded = true;
                throw new IOException("request body exceeds " + max + " bytes");
            }
        }
    }

    private static void problem(HttpServletResponse res, int status, String code, String detail, String requestId)
            throws IOException {
        if (res.isCommitted()) {
            return;
        }
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        res.getWriter().write("""
                {"type":"https://docs.meridian.example/errors/%s","title":"%s","status":%d,"code":"%s","detail":"%s","request_id":"%s"}"""
                .formatted(code, code.replace('_', ' '), status, code, detail.replace("\"", "'"), requestId));
    }
}
