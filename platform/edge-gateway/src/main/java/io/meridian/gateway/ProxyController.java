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
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Streams each /v1 request to the owning service and the response back (virtual threads, no buffering of bodies).
 * Adds X-Request-Id and X-Forwarded-* headers; maps unknown routes and unreachable services to problem+json.
 */
@RestController
public class ProxyController {
    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);
    private static final Set<String> HOP_BY_HOP = Set.of("connection", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length",
            "expect");

    /**
     * Upstream of a service: {@code meridian.gateway.services.<name>} override, else {@code url-template} with
     * {@code {name}} / {@code {port}} placeholders (e.g. {@code http://{name}} in Kubernetes), else
     * http://localhost:<port>.
     */
    @ConfigurationProperties(prefix = "meridian.gateway")
    public record GatewayProperties(Map<String, String> services, Duration timeout, String urlTemplate) {
        public GatewayProperties {
            services = services == null ? Map.of() : services;
            timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        }
    }

    private final RouteTable routes;
    private final GatewayProperties props;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public ProxyController(RouteTable routes, GatewayProperties props) {
        this.routes = routes;
        this.props = props;
        log.info("Edge gateway loaded {} routes", routes.size());
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

        HttpRequest.Builder out = HttpRequest.newBuilder(URI.create(target)).timeout(props.timeout());
        for (String name : Collections.list(req.getHeaderNames())) {
            if (!HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) {
                for (String v : Collections.list(req.getHeaders(name))) {
                    out.header(name, v);
                }
            }
        }
        out.setHeader("X-Request-Id", requestId);
        out.header("X-Forwarded-For", req.getRemoteAddr());
        out.header("X-Forwarded-Proto", req.getScheme());
        out.header("X-Forwarded-Host", String.valueOf(req.getHeader("Host")));
        boolean hasBody = req.getContentLengthLong() > 0 || req.getHeader("Transfer-Encoding") != null;
        out.method(req.getMethod(), hasBody
                ? HttpRequest.BodyPublishers.ofInputStream(() -> {
                    try {
                        return req.getInputStream();
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
            log.warn("Upstream {} unreachable at {}: {}", r.service(), base, e.toString());
            problem(res, 503, "service_unavailable", r.service() + " is not reachable", requestId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            problem(res, 503, "service_unavailable", "request interrupted", requestId);
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
