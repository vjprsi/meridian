package io.meridian.web.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class OutboundHttpTest {
    static HttpServer server;
    static int port;
    static final AtomicInteger redirectedHits = new AtomicInteger();

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", ex -> {
            byte[] b = "hello".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.createContext("/big", ex -> {
            byte[] b = new byte[10_000];
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.createContext("/redirect", ex -> {
            ex.getResponseHeaders().add("Location", "http://127.0.0.1:" + port + "/redirected");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/redirected", ex -> {
            redirectedHits.incrementAndGet();
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.createContext("/slow", ex -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    static OutboundUrlPolicy devLocal(long maxBytes, Duration total) {
        return new OutboundUrlPolicy(new OutboundUrlPolicy.Settings(true, true, Set.of(), Duration.ofSeconds(2),
                Duration.ofSeconds(5), total, maxBytes), true);
    }

    @Test
    void fetchesWithinLimitsAndNeverFollowsRedirects() throws Exception {
        try (OutboundHttp http = new OutboundHttp(devLocal(1_000, Duration.ofSeconds(10)))) {
            OutboundHttp.Response ok = http.get("http://127.0.0.1:" + port + "/ok", Map.of());
            assertThat(ok.status()).isEqualTo(200);
            assertThat(new String(ok.body(), StandardCharsets.UTF_8)).isEqualTo("hello");

            OutboundHttp.Response big = http.get("http://127.0.0.1:" + port + "/big", Map.of());
            assertThat(big.truncated()).isTrue();
            assertThat(big.body()).hasSize(1_000);

            OutboundHttp.Response redirect = http.get("http://127.0.0.1:" + port + "/redirect", Map.of());
            assertThat(redirect.status()).isEqualTo(302);
            assertThat(redirectedHits).hasValue(0);
        }
    }

    @Test
    void totalDeadlineCancelsSlowExchanges() throws Exception {
        try (OutboundHttp http = new OutboundHttp(devLocal(1_000, Duration.ofMillis(500)))) {
            assertThatThrownBy(() -> http.get("http://127.0.0.1:" + port + "/slow", Map.of()))
                    .isInstanceOf(OutboundHttp.Failure.class)
                    .extracting(e -> ((OutboundHttp.Failure) e).code()).isEqualTo(OutboundHttp.Failure.CODE_TIMEOUT);
        }
    }

    @Test
    void strictPolicyRefusesLoopbackBeforeConnecting() throws Exception {
        OutboundUrlPolicy strict = new OutboundUrlPolicy(new OutboundUrlPolicy.Settings(true, true, Set.of(),
                null, null, null, 0), false); // relaxations requested but not in dev mode -> ignored
        try (OutboundHttp http = new OutboundHttp(strict)) {
            assertThatThrownBy(() -> http.get("https://127.0.0.1:" + port + "/ok", Map.of()))
                    .isInstanceOf(OutboundUrlPolicy.RejectedException.class);
            assertThatThrownBy(() -> http.get("http://127.0.0.1:" + port + "/ok", Map.of()))
                    .isInstanceOf(OutboundUrlPolicy.RejectedException.class);
        }
    }

    @Test
    void dnsRebindingBetweenCheckAndConnectIsBlockedAtConnectTime() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        // first answer is public (passes the pre-flight check), every later answer points at loopback
        OutboundUrlPolicy rebinding = new OutboundUrlPolicy(OutboundUrlPolicy.Settings.strict(), false, host ->
                new InetAddress[] {InetAddress.getByName(lookups.getAndIncrement() == 0 ? "93.184.216.34" : "127.0.0.1")});
        try (OutboundHttp http = new OutboundHttp(rebinding)) {
            assertThatThrownBy(() -> http.get("https://rebind.attacker.example:" + port + "/ok", Map.of()))
                    .isInstanceOf(OutboundHttp.Failure.class)
                    .extracting(e -> ((OutboundHttp.Failure) e).code()).isEqualTo(OutboundHttp.Failure.CODE_BLOCKED);
        }
        assertThat(lookups.get()).isGreaterThanOrEqualTo(2);
    }
}
