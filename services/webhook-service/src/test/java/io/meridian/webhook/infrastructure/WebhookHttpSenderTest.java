package io.meridian.webhook.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.meridian.web.outbound.OutboundHttp;
import io.meridian.web.outbound.OutboundUrlPolicy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** SEC-09: deliveries only reach public destinations and keep only a short sanitised response snippet. */
class WebhookHttpSenderTest {
    static HttpServer server;
    static int port;
    static final AtomicInteger hits = new AtomicInteger();

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", ex -> {
            hits.incrementAndGet();
            ex.getRequestBody().readAllBytes();
            byte[] b = ("{\"secret\":\"" + "x".repeat(5000) + "\"}\r\n\u0000\u001b[31m").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.createContext("/redirect", ex -> {
            hits.incrementAndGet();
            ex.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data/");
            ex.sendResponseHeaders(307, -1);
            ex.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    static WebhookHttpSender sender(OutboundUrlPolicy p) {
        return new WebhookHttpSender(new OutboundHttp(p));
    }

    @Test
    void internalDestinationsAreRefusedWithoutConnectingAndWithoutLeakingErrors() {
        int before = hits.get();
        // allow-insecure-localhost outside dev mode is ignored by the shared policy
        WebhookHttpSender strict = sender(WebhookHttpSender.senderPolicy(
                new OutboundUrlPolicy(OutboundUrlPolicy.Settings.strict(), false), 2_000, true));
        for (String url : new String[] {"http://127.0.0.1:" + port + "/hook", "https://127.0.0.1:" + port + "/hook",
                "https://169.254.169.254/latest/meta-data/", "https://[fd00:ec2::254]/", "https://10.1.2.3/"}) {
            WebhookHttpSender.Result r = strict.post(url, Map.of(), "{}");
            assertThat(r.statusCode()).as(url).isNull();
            assertThat(r.responseBody()).isNull();
            assertThat(r.error()).as(url).isIn("https_required", "destination_not_allowed");
        }
        assertThat(hits).hasValue(before);
    }

    @Test
    void aNameThatRebindsToLoopbackAtConnectTimeIsRefused() {
        int before = hits.get();
        AtomicInteger lookups = new AtomicInteger();
        OutboundUrlPolicy rebinding = new OutboundUrlPolicy(OutboundUrlPolicy.Settings.strict(), false, host ->
                new InetAddress[] {InetAddress.getByName(lookups.getAndIncrement() == 0 ? "93.184.216.34" : "127.0.0.1")});
        WebhookHttpSender.Result r = sender(rebinding).post("https://hooks.attacker.example:" + port + "/hook", Map.of(), "{}");
        assertThat(r.error()).isEqualTo("destination_not_allowed");
        assertThat(hits).hasValue(before);
    }

    @Test
    void onlyAShortSanitisedSnippetIsKeptAndRedirectsAreNotFollowed() {
        OutboundUrlPolicy devLocal = new OutboundUrlPolicy(new OutboundUrlPolicy.Settings(true, true, Set.of(),
                null, null, null, 0), true);
        WebhookHttpSender local = sender(WebhookHttpSender.senderPolicy(devLocal, 5_000, true));
        WebhookHttpSender.Result ok = local.post("http://127.0.0.1:" + port + "/hook", Map.of(), "{}");
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.responseBody()).hasSizeLessThanOrEqualTo(WebhookHttpSender.SNIPPET_CHARS).startsWith("{\"secret\":\"xxx");
        assertThat(ok.responseBody().chars()).noneMatch(Character::isISOControl);

        WebhookHttpSender.Result redirect = local.post("http://127.0.0.1:" + port + "/redirect", Map.of(), "{}");
        assertThat(redirect.statusCode()).isEqualTo(307);
        assertThat(redirect.error()).isNull();
    }

    @Test
    void snippetStripsControlCharactersAndCollapsesWhitespace() {
        assertThat(WebhookHttpSender.snippet("a\r\n\tb\u0000c\u001b[0m  d")).isEqualTo("a bc[0m d");
        assertThat(WebhookHttpSender.snippet("\r\n")).isNull();
        assertThat(WebhookHttpSender.snippet("y".repeat(1000))).hasSize(WebhookHttpSender.SNIPPET_CHARS);
    }
}
