package io.meridian.router;

import io.meridian.common.id.Ids;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Routes each /v1 request to the gateway of the tenant's cell and streams the response back (virtual threads, no
 * body buffering): resolve the tenant ({@link TenantResolver}), look up its placement ({@link PlacementCache}),
 * apply {@link RoutingPolicy} (frozen tenants, OFFLINE home cell -> standby, READ_ONLY cells), then proxy with the
 * same header policy as the edge gateway (SEC-13 internal headers dropped in jwt mode, SEC-16 forwarding headers set
 * by the router, SEC-26 body cap). Adds {@code X-Meridian-Cell} to responses; metrics per cell and outcome.
 */
@RestController
public class RouterController {
    private static final Logger log = LoggerFactory.getLogger(RouterController.class);

    private final TenantResolver resolver;
    private final PlacementCache placements;
    private final CellDirectory cells;
    private final PublicRoutes publicRoutes;
    private final HeaderPolicy headers;
    private final RouterProperties props;
    private final CellMaintenance maintenance;
    private final MeterRegistry meters;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .version(HttpClient.Version.HTTP_1_1).build();

    public RouterController(TenantResolver resolver, PlacementCache placements, CellDirectory cells,
                            PublicRoutes publicRoutes, HeaderPolicy headers, RouterProperties props,
                            CellMaintenance maintenance, MeterRegistry meters) {
        this.resolver = resolver;
        this.placements = placements;
        this.cells = cells;
        this.publicRoutes = publicRoutes;
        this.headers = headers;
        this.props = props;
        this.maintenance = maintenance;
        this.meters = meters;
        if (headers.devMode()) {
            log.warn("Cell router in dev security mode: tenants come from X-Tenant-Id. Local only.");
        }
    }

    /** Load balancer / Route 53 health check: ready once the cell directory has been loaded from the registry. */
    @GetMapping("/healthz")
    public ResponseEntity<String> healthz() {
        return maintenance.loaded() ? ResponseEntity.ok("{\"status\":\"UP\"}")
                : ResponseEntity.status(503).body("{\"status\":\"STARTING\"}");
    }

    @RequestMapping("/v1/**")
    public void route(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String requestId = req.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank() || requestId.length() > 128) {
            requestId = "req_" + Ids.newId();
        }
        res.setHeader("X-Request-Id", requestId);
        String method = req.getMethod();
        String path = req.getRequestURI();

        TenantResolver.Resolution who = resolver.resolve(req, publicRoutes.isPublic(method, path));
        if (!who.ok()) {
            count("none", "refused_" + who.code());
            problem(res, who.status(), who.code(), who.detail(), requestId, false);
            return;
        }
        UUID tenant = who.tenant();
        Optional<Registry.TenantRouting> routing;
        try {
            routing = placements.get(tenant);
        } catch (Registry.UnavailableException e) {
            count("none", "registry_unavailable");
            problem(res, 503, "routing_unavailable", "The tenant's placement cannot be resolved right now", requestId, true);
            return;
        }
        if (routing.isEmpty()) {
            count("none", "unknown_tenant");
            problem(res, 404, "tenant_not_found", "No cell serves this tenant", requestId, false);
            return;
        }
        Registry.TenantRouting r = routing.get();
        RoutingPolicy.Decision decision = RoutingPolicy.decide(r, method, cells.view(r.primaryCellId()),
                cells.view(r.standbyCellId()), props.health().autoFailover());
        if (decision instanceof RoutingPolicy.Refuse refuse) {
            String cell = refuse.cell() == null ? "none" : refuse.cell().code();
            count(cell, refuse.code());
            if (refuse.cell() != null) {
                res.setHeader("X-Meridian-Cell", refuse.cell().code());
            }
            problem(res, refuse.status(), refuse.code(), refuse.detail(), requestId, refuse.status() == 503);
            return;
        }
        RoutingPolicy.Route route = (RoutingPolicy.Route) decision;
        if (route.failover()) {
            meters.counter("meridian.router.failover.requests", "from_cell", cells.view(r.primaryCellId()).code(),
                    "to_cell", route.cell().code()).increment();
        }
        proxy(req, res, route.cell(), route.failover(), requestId);
    }

    private void proxy(HttpServletRequest req, HttpServletResponse res, CellDirectory.View cell, boolean failover,
                       String requestId) throws IOException {
        String target = cell.url() + req.getRequestURI() + (req.getQueryString() == null ? "" : "?" + req.getQueryString());
        long maxBody = props.maxBodySize().toBytes();
        if (req.getContentLengthLong() > maxBody) {
            count(cell.code(), "payload_too_large");
            problem(res, 413, "payload_too_large", "Request body exceeds " + maxBody + " bytes", requestId, false);
            return;
        }
        HttpRequest.Builder out = HttpRequest.newBuilder(URI.create(target)).timeout(props.timeout());
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
        Timer.Sample sample = Timer.start(meters);
        String outcome = failover ? "failover" : "routed";
        try {
            HttpResponse<InputStream> upstream = client.send(out.build(), HttpResponse.BodyHandlers.ofInputStream());
            res.setStatus(upstream.statusCode());
            upstream.headers().map().forEach((name, values) -> {
                String lower = name.toLowerCase(Locale.ROOT);
                if (!HeaderPolicy.HOP_BY_HOP.contains(lower) && !lower.equals("x-request-id")
                        && !lower.equals("x-meridian-cell") && !name.startsWith(":")) {
                    values.forEach(v -> res.addHeader(name, v));
                }
            });
            res.setHeader("X-Meridian-Cell", cell.code());
            try (InputStream body = upstream.body()) {
                body.transferTo(res.getOutputStream());
            }
        } catch (HttpTimeoutException e) {
            outcome = "upstream_timeout";
            problem(res, 504, "upstream_timeout", "Cell " + cell.code() + " did not answer in time", requestId, false);
        } catch (IOException e) {
            if (sent[0] != null && sent[0].exceeded()) {
                outcome = "payload_too_large";
                problem(res, 413, "payload_too_large", "Request body exceeds " + maxBody + " bytes", requestId, false);
            } else {
                outcome = "upstream_unreachable";
                log.warn("Cell {} unreachable at {}: {}", cell.code(), cell.url(), e.toString());
                problem(res, 503, "cell_unreachable", "Cell " + cell.code() + " is not reachable", requestId, true);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            outcome = "interrupted";
            problem(res, 503, "service_unavailable", "request interrupted", requestId, true);
        } finally {
            sample.stop(meters.timer("meridian.router.upstream", "cell", cell.code(), "outcome", outcome));
            count(cell.code(), outcome);
        }
    }

    private void count(String cell, String outcome) {
        meters.counter("meridian.router.requests", "cell", cell, "outcome", outcome).increment();
    }

    private void problem(HttpServletResponse res, int status, String code, String detail, String requestId,
                         boolean retryAfter) throws IOException {
        if (res.isCommitted()) {
            return;
        }
        res.setStatus(status);
        if (retryAfter) {
            res.setHeader("Retry-After", String.valueOf(Math.max(1, props.retryAfter().toSeconds())));
        }
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        res.getWriter().write("""
                {"type":"https://docs.meridian.example/errors/%s","title":"%s","status":%d,"code":"%s","detail":"%s","request_id":"%s"}"""
                .formatted(code, code.replace('_', ' '), status, code, detail.replace("\"", "'"), requestId));
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
}
