package io.meridian.router;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The router reads the registry with its own client (meridian-cell-router, scope platform:routing-read only), not the
 * shared meridian-service client that carries meridian:internal and platform:operator.
 */
class RegistryClientAuthTest {
    HttpServer server;
    final List<String> tokenForms = new CopyOnWriteArrayList<>();
    final List<String> registryAuth = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void defaultsAreTheRoutersOwnClientWithTheNarrowScope() {
        RouterProperties p = new Binder(new MapConfigurationPropertySource(Map.of(
                "meridian.router.auth.tokenUri", "https://idp/token",
                "meridian.router.auth.clientSecret", "s3cret")))
                .bind("meridian.router", RouterProperties.class).get();
        assertThat(p.auth().clientId()).isEqualTo("meridian-cell-router");
        assertThat(p.auth().scope()).isEqualTo("platform:routing-read").doesNotContain("meridian:internal", "platform:operator");
    }

    @Test
    void registryReadsUseAClientCredentialsTokenOfTheRoutersClientAndScope() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", ex -> {
            tokenForms.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(ex, 200, "{\"access_token\":\"router-token\",\"expires_in\":300}");
        });
        server.createContext("/v1/platform/cells", ex -> {
            registryAuth.add(ex.getRequestHeaders().getFirst("Authorization"));
            reply(ex, 200, "{\"data\":[],\"has_more\":false}");
        });
        server.createContext("/v1/platform/tenants", ex -> {
            registryAuth.add(ex.getRequestHeaders().getFirst("Authorization"));
            reply(ex, 404, "{}");
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        RouterProperties props = new Binder(new MapConfigurationPropertySource(Map.of(
                "meridian.router.registryUrl", base,
                "meridian.router.auth.tokenUri", base + "/token",
                "meridian.router.auth.clientSecret", "router-secret")))
                .bind("meridian.router", RouterProperties.class).get();
        RegistryClient client = new RegistryClient(props, JsonMapper.builder().build());

        assertThat(client.cells()).isEmpty();
        assertThat(client.routing(UUID.randomUUID(), c -> { })).isEmpty();

        assertThat(tokenForms).hasSize(1);   // cached for the second call
        Map<String, String> form = form(tokenForms.get(0));
        assertThat(form).containsEntry("grant_type", "client_credentials")
                .containsEntry("client_id", "meridian-cell-router")
                .containsEntry("client_secret", "router-secret")
                .containsEntry("scope", "platform:routing-read");
        assertThat(registryAuth).containsOnly("Bearer router-token").hasSize(2);
    }

    private static Map<String, String> form(String body) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (String kv : body.split("&")) {
            String[] p = kv.split("=", 2);
            out.put(URLDecoder.decode(p[0], StandardCharsets.UTF_8),
                    p.length > 1 ? URLDecoder.decode(p[1], StandardCharsets.UTF_8) : "");
        }
        return out;
    }

    private static void reply(HttpExchange ex, int status, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }
}
