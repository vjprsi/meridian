package io.meridian.customerchannel;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.meridian.customerchannel.application.IdentityProviderSessions;
import io.meridian.customerchannel.application.IdentityProviderSessions.IdpSession;
import io.meridian.customerchannel.config.ChannelProperties;
import io.meridian.customerchannel.config.ChannelProperties.IdentityProvider;
import io.meridian.customerchannel.config.ChannelProperties.Sessions;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Keycloak admin client of the customer sessions: request shapes against a fake Keycloak, and (when
 * MERIDIAN_KEYCLOAK_TEST_URL points at a Keycloak with the generated meridian realm) against the real one.
 */
class IdentityProviderSessionsTest {
    static final String USER = "6f1c2a7e-0000-4000-8000-000000000001";
    final JsonMapper json = JsonMapper.builder().build();
    final List<String> calls = new CopyOnWriteArrayList<>();
    HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private IdentityProviderSessions client(URI base) {
        return new IdentityProviderSessions(new ChannelProperties(null, null, new Sessions(null, null, null,
                new IdentityProvider(base, "meridian", "meridian-customer-sessions", "secret", Duration.ofSeconds(5))),
                null), json);
    }

    private URI fakeKeycloak() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            calls.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + (auth == null ? "" : " " + auth));
            String path = ex.getRequestURI().getPath();
            String body = "";
            int status = 204;
            if (path.endsWith("/openid-connect/token")) {
                String form = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                status = form.contains("grant_type=client_credentials") && form.contains("client_secret=secret")
                        ? 200 : 401;
                body = "{\"access_token\":\"admin-token\",\"expires_in\":300}";
            } else if (path.endsWith("/sessions") && ex.getRequestMethod().equals("GET")) {
                status = 200;
                body = "[{\"id\":\"s-1\",\"ipAddress\":\"10.0.0.7\",\"start\":1791369598000,"
                        + "\"lastAccess\":1791369603000,\"clients\":{\"c\":\"meridian-customer-web\"}}]";
            } else if (path.endsWith("/credentials")) {
                status = 200;
                body = "[{\"id\":\"p\",\"type\":\"password\",\"createdDate\":1},"
                        + "{\"id\":\"o\",\"type\":\"otp\",\"userLabel\":\"Phone\",\"createdDate\":1791369598000},"
                        + "{\"id\":\"w\",\"type\":\"webauthn-passwordless\",\"userLabel\":\"Passkey\",\"createdDate\":2}]";
            } else if (path.endsWith("/sessions/gone")) {
                status = 404;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                ex.getResponseBody().write(bytes);
            }
            ex.close();
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Test
    void callsTheAdminApiForTheUserWithAClientCredentialsToken() throws Exception {
        IdentityProviderSessions idp = client(fakeKeycloak());
        List<IdpSession> sessions = idp.sessions(USER).orElseThrow();
        assertThat(sessions).singleElement().satisfies(s -> {
            assertThat(s.id()).isEqualTo("s-1");
            assertThat(s.ipAddress()).isEqualTo("10.0.0.7");
            assertThat(s.clients()).containsExactly("meridian-customer-web");
        });
        assertThat(idp.factors(USER).orElseThrow()).extracting(IdentityProviderSessions.Factor::type)
                .containsExactly("otp", "webauthn-passwordless");          // the password is not a second factor
        assertThat(idp.endSession("s-1")).isTrue();
        assertThat(idp.endSession("gone")).isTrue();                        // already ended
        assertThat(idp.endAll(USER)).isTrue();
        assertThat(calls).containsSubsequence(
                "POST /realms/meridian/protocol/openid-connect/token",
                "GET /admin/realms/meridian/users/" + USER + "/sessions Bearer admin-token",
                "GET /admin/realms/meridian/users/" + USER + "/credentials Bearer admin-token",
                "DELETE /admin/realms/meridian/sessions/s-1 Bearer admin-token",
                "POST /admin/realms/meridian/users/" + USER + "/logout Bearer admin-token");
        assertThat(calls.stream().filter(c -> c.contains("/token")).count()).isEqualTo(1);   // token cached
    }

    @Test
    void refusesIdsThatCouldChangeThePath() throws Exception {
        IdentityProviderSessions idp = client(fakeKeycloak());
        assertThat(idp.sessions("../../clients")).isEmpty();
        assertThat(idp.endSession("a/b")).isFalse();
        assertThat(idp.endAll(null)).isFalse();
        assertThat(calls).isEmpty();
    }

    @Test
    void unreachableIdentityProviderIsNotAnError() {
        IdentityProviderSessions idp = client(URI.create("http://127.0.0.1:9"));
        assertThat(idp.sessions(USER)).isEmpty();
        assertThat(idp.endSession("s-1")).isFalse();
    }

    /**
     * Real Keycloak with the generated realm (tools/keycloak_realm.py): demo.customer signs in with password + TOTP,
     * the sessions client lists that session and its factors, then ends it.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "MERIDIAN_KEYCLOAK_TEST_URL", matches = "http.+")
    void againstARealKeycloak() throws Exception {
        URI base = URI.create(System.getenv("MERIDIAN_KEYCLOAK_TEST_URL"));
        IdentityProviderSessions idp = new IdentityProviderSessions(new ChannelProperties(null, null,
                new Sessions(null, null, null, new IdentityProvider(base, "meridian", "meridian-customer-sessions",
                        "meridian-customer-sessions-secret", null)), null), json);
        HttpClient http = HttpClient.newHttpClient();
        String form = form(Map.of("grant_type", "password", "client_id", "meridian-customer-web",
                "username", "demo.customer", "password", "Meridian#Customer2026", "scope", "openid",
                "totp", totp("MeridianDemoTotp2026".getBytes(StandardCharsets.UTF_8))));
        HttpResponse<String> tok = http.send(HttpRequest.newBuilder(base.resolve(
                        "/realms/meridian/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(tok.statusCode()).as(tok.body()).isEqualTo(200);
        JsonNode claims = json.readTree(java.util.Base64.getUrlDecoder().decode(
                json.readTree(tok.body()).path("access_token").asString().split("\\.")[1]));
        String sub = claims.path("sub").asString();
        String sid = claims.path("sid").asString();
        assertThat(idp.sessions(sub).orElseThrow()).extracting(IdpSession::id).contains(sid);
        assertThat(idp.factors(sub).orElseThrow()).extracting(IdentityProviderSessions.Factor::type).contains("otp");
        assertThat(idp.endSession(sid)).isTrue();
        assertThat(idp.sessions(sub).orElseThrow()).extracting(IdpSession::id).doesNotContain(sid);
        assertThat(idp.endAll(sub)).isTrue();
    }

    private static String form(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        fields.forEach((k, v) -> sb.append(sb.isEmpty() ? "" : "&").append(k).append('=')
                .append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return sb.toString();
    }

    private static String totp(byte[] secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(secret, "HmacSHA1"));
        byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(System.currentTimeMillis() / 30_000).array());
        int o = h[h.length - 1] & 15;
        int code = ((h[o] & 0x7f) << 24 | (h[o + 1] & 0xff) << 16 | (h[o + 2] & 0xff) << 8 | (h[o + 3] & 0xff))
                % 1_000_000;
        return String.format("%06d", code);
    }
}
