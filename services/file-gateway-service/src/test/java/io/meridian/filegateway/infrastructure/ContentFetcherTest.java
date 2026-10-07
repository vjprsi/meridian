package io.meridian.filegateway.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.meridian.common.error.ApiException;
import io.meridian.web.outbound.OutboundUrlPolicy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** SEC-08: content_url cannot reach internal / metadata endpoints and redirects are refused. */
class ContentFetcherTest {
    static HttpServer server;
    static int port;
    static final AtomicInteger internalHits = new AtomicInteger();

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/file.csv", ex -> {
            byte[] b = "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.createContext("/redirect", ex -> {
            ex.getResponseHeaders().add("Location", "http://127.0.0.1:" + port + "/internal");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/internal", ex -> {
            internalHits.incrementAndGet();
            byte[] b = "SECRET".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(500, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    static ContentFetcher fetcher(boolean devLocal, long max) {
        OutboundUrlPolicy policy = new OutboundUrlPolicy(new OutboundUrlPolicy.Settings(devLocal, devLocal, Set.of(),
                null, null, null, 0), devLocal);
        return new ContentFetcher(policy, max, 10_000);
    }

    static void unprocessable(Runnable r, String code) {
        assertThatThrownBy(r::run).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(code));
    }

    @Test
    void metadataLoopbackPrivateAndPlainHttpTargetsAreRefusedWithoutConnecting() {
        int before = internalHits.get();
        ContentFetcher strict = fetcher(false, 1_000);
        unprocessable(() -> strict.fetch("http://169.254.169.254/latest/meta-data/iam/security-credentials/role"),
                "invalid_content_url");
        unprocessable(() -> strict.fetch("https://169.254.169.254/latest/meta-data/"), "invalid_content_url");
        unprocessable(() -> strict.fetch("https://127.0.0.1:" + port + "/internal"), "invalid_content_url");
        unprocessable(() -> strict.fetch("https://10.0.0.8/actuator/env"), "invalid_content_url");
        unprocessable(() -> strict.fetch("https://[::ffff:127.0.0.1]:" + port + "/internal"), "invalid_content_url");
        unprocessable(() -> strict.fetch("https://localhost:" + port + "/internal"), "invalid_content_url");
        unprocessable(() -> strict.fetch("file:///etc/passwd"), "invalid_content_url");
        assertThat(internalHits).hasValue(before);
    }

    @Test
    void redirectsAreNotFollowedAndRemoteBodiesAreNotEchoed() {
        int before = internalHits.get();
        ContentFetcher local = fetcher(true, 1_000);
        assertThat(new String(local.fetch("http://127.0.0.1:" + port + "/file.csv"), StandardCharsets.UTF_8))
                .isEqualTo("a,b\n1,2\n");
        assertThatThrownBy(() -> local.fetch("http://127.0.0.1:" + port + "/redirect"))
                .isInstanceOf(ApiException.class).hasMessageContaining("redirect");
        assertThat(internalHits).hasValue(before);
        assertThatThrownBy(() -> local.fetch("http://127.0.0.1:" + port + "/internal"))
                .isInstanceOf(ApiException.class).hasMessageContaining("HTTP 500").hasMessageNotContaining("SECRET");
    }

    @Test
    void oversizedContentIsRefused() {
        unprocessable(() -> fetcher(true, 4).fetch("http://127.0.0.1:" + port + "/file.csv"), "file_too_large");
    }
}
