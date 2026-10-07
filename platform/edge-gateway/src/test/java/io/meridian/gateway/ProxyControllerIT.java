package io.meridian.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** SEC-13 / SEC-16 / SEC-26 / SEC-31 through the real proxy against a stub upstream (jwt mode, the default). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.gateway.max-body-size=1KB"})
class ProxyControllerIT {
    static final Map<String, List<String>> seen = new ConcurrentHashMap<>();
    static final HttpServer upstream;

    static {
        try {
            upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        upstream.createContext("/", ex -> {
            seen.clear();
            ex.getRequestHeaders().forEach((k, v) -> seen.put(k.toLowerCase(), v));
            ex.getRequestBody().readAllBytes();
            byte[] body = "{\"data\":[]}".getBytes();
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        upstream.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("meridian.gateway.services.identity-service", () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        upstream.stop(0);
    }

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newHttpClient();

    @Test
    void internalHeadersAreStrippedAndClientAddressComesFromTheSocket() throws Exception {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/access-policies"))
                .header("X-Tenant-Id", "0199a000-0000-7000-8000-00000000000a").header("X-User", "root")
                .header("X-Roles", "admin").header("X-MFA-Verified", "true").header("X-Customer-Id", "c")
                .header("X-On-Behalf-Of-Customer", "c").header("X-Meridian-Service", "ledger-service")
                .header("X-Auth-Time", "1").header("X-Forwarded-For", "6.6.6.6").header("Forwarded", "for=6.6.6.6")
                .header("Authorization", "Bearer abc").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(seen).doesNotContainKeys("x-tenant-id", "x-user", "x-roles", "x-mfa-verified", "x-customer-id",
                "x-on-behalf-of-customer", "x-meridian-service", "x-auth-time", "forwarded");
        assertThat(seen.get("x-forwarded-for")).containsExactly("127.0.0.1");
        assertThat(seen.get("authorization")).containsExactly("Bearer abc");
    }

    @Test
    void oversizedBodiesAreRefusedWith413() throws Exception {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/access-policies"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("x".repeat(4096))).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(413);
        assertThat(res.body()).contains("payload_too_large");
    }

    @Test
    void onlyHealthAndInfoAreExposed() throws Exception {
        HttpResponse<String> metrics = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/metrics"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(metrics.statusCode()).isEqualTo(404);
    }
}
