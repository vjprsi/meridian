package io.meridian.mover;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.lifecycle.DataLifecycleService;
import io.meridian.web.lifecycle.PersonalDataCatalog;
import io.meridian.web.tenancy.TenantAwareDataSource;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * One cell for the mover IT: the real data-lifecycle move API of libs/meridian-web ({@link DataLifecycleService}) for
 * each service over that cell's database (runtime role meridian_app, row-level security on), served at
 * {@code http://localhost:<port>/<service>/v1/internal/data-lifecycle/move/*}, plus an in-memory S3 bucket.
 */
final class FakeCell implements AutoCloseable {
    static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    final String bucket;
    final Map<String, DataLifecycleService> services = new ConcurrentHashMap<>();
    final Map<String, byte[]> objects = new ConcurrentSkipListMap<>();
    /** Fails the next N import calls with 503 (transient failure injection). */
    final AtomicInteger failImports = new AtomicInteger();
    final AtomicInteger imports = new AtomicInteger();
    private final HttpServer server;

    FakeCell(String jdbcBase, Map<String, String> serviceSchemas, String bucket) throws IOException {
        this.bucket = bucket;
        serviceSchemas.forEach((svc, schema) -> {
            var ds = new TenantAwareDataSource(new DriverManagerDataSource(jdbcBase + "&currentSchema=" + schema,
                    "meridian_app", "meridian_app"));
            services.put(svc, new DataLifecycleService(JdbcClient.create(ds),
                    new TransactionTemplate(new DataSourceTransactionManager(ds)), JSON,
                    PersonalDataCatalog.of(JSON.createObjectNode()), svc, Map.of()));
        });
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    String servicesUrlTemplate() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/{name}";
    }

    MoverProperties.S3 s3() {
        return new MoverProperties.S3("http://127.0.0.1:" + server.getAddress().getPort() + "/s3", bucket, "us-east-1",
                "k", "s");
    }

    static String md5(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(b));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (path.startsWith("/s3/")) {
                s3(ex, path.substring(4));
                return;
            }
            String[] parts = path.split("/", 3);                     // "", service, rest
            DataLifecycleService svc = services.get(parts[1]);
            String op = parts.length > 2 ? parts[2] : "";
            if (svc == null || !op.startsWith("v1/internal/data-lifecycle/move/")) {
                send(ex, 404, "{\"code\":\"not_found\"}");
                return;
            }
            if (!"data-mover".equals(ex.getRequestHeaders().getFirst("X-Meridian-Service"))) {
                send(ex, 403, "{\"code\":\"caller_not_allowed\"}");
                return;
            }
            JsonNode body = JSON.readTree(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            TenantContext.set(UUID.fromString(ex.getRequestHeaders().getFirst("X-Tenant-Id")), "data-mover");
            try {
                ObjectNode out = switch (op.substring("v1/internal/data-lifecycle/move/".length())) {
                    case "tables" -> svc.moveTables();
                    case "extract" -> svc.moveExtract(body);
                    case "import" -> {
                        imports.incrementAndGet();
                        if (failImports.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                            throw new ApiException(503, "unavailable", "injected failure");
                        }
                        yield svc.moveImport(body);
                    }
                    default -> throw new ApiException(404, "not_found", op);
                };
                send(ex, 200, JSON.writeValueAsString(out));
            } finally {
                TenantContext.clear();
            }
        } catch (ApiException e) {
            send(ex, e.status(), "{\"code\":\"" + e.code() + "\",\"detail\":" + JSON.writeValueAsString(e.getMessage()) + "}");
        } catch (RuntimeException e) {
            send(ex, 500, "{\"code\":\"internal\",\"detail\":" + JSON.writeValueAsString(String.valueOf(e.getMessage())) + "}");
        }
    }

    /** Path-style S3: list (ListObjectsV2), head, get, put on this cell's bucket. Signatures are not checked. */
    private void s3(HttpExchange ex, String rest) throws IOException {
        String key = rest.contains("/") ? rest.substring(rest.indexOf('/') + 1) : "";
        String method = ex.getRequestMethod();
        if (key.isEmpty() && "GET".equals(method)) {
            Map<String, String> q = new java.util.HashMap<>();
            for (String kv : ex.getRequestURI().getRawQuery().split("&")) {
                String[] p = kv.split("=", 2);
                q.put(java.net.URLDecoder.decode(p[0], StandardCharsets.UTF_8),
                        p.length > 1 ? java.net.URLDecoder.decode(p[1], StandardCharsets.UTF_8) : "");
            }
            String prefix = q.getOrDefault("prefix", "");
            String after = q.get("start-after");
            int max = Integer.parseInt(q.getOrDefault("max-keys", "1000"));
            StringBuilder sb = new StringBuilder("<ListBucketResult>");
            int n = 0;
            boolean truncated = false;
            for (var e : objects.entrySet()) {
                if (!e.getKey().startsWith(prefix) || (after != null && e.getKey().compareTo(after) <= 0)) {
                    continue;
                }
                if (n == max) {
                    truncated = true;
                    break;
                }
                sb.append("<Contents><Key>").append(e.getKey()).append("</Key><Size>").append(e.getValue().length)
                        .append("</Size><ETag>&quot;").append(md5(e.getValue())).append("&quot;</ETag></Contents>");
                n++;
            }
            sb.append("<IsTruncated>").append(truncated).append("</IsTruncated></ListBucketResult>");
            send(ex, 200, sb.toString());
            return;
        }
        key = java.net.URLDecoder.decode(key, StandardCharsets.UTF_8);
        switch (method) {
            case "PUT" -> {
                objects.put(key, ex.getRequestBody().readAllBytes());
                send(ex, 200, "");
            }
            case "HEAD" -> {
                byte[] b = objects.get(key);
                if (b == null) {
                    ex.sendResponseHeaders(404, -1);
                } else {
                    ex.getResponseHeaders().add("ETag", "\"" + md5(b) + "\"");
                    ex.getResponseHeaders().add("Content-Length", Integer.toString(b.length));
                    ex.sendResponseHeaders(200, -1);
                }
                ex.close();
            }
            default -> {
                byte[] b = objects.get(key);
                if (b == null) {
                    send(ex, 404, "");
                } else {
                    ex.sendResponseHeaders(200, b.length);
                    ex.getResponseBody().write(b);
                    ex.close();
                }
            }
        }
    }

    static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            ex.getResponseBody().write(b);
        }
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
