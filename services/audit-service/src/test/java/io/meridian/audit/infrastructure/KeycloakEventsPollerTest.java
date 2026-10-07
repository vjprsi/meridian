package io.meridian.audit.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.meridian.audit.application.AuditRecorder.NewAuditEvent;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

/**
 * GAP-06: Keycloak user and admin events become audit events once each (paging newest-first, window with overlap,
 * de-duplication by source event id), without secrets or login names, with snake_case keys for the PII masker.
 */
class KeycloakEventsPollerTest {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000f0";
    final JsonMapper json = JsonMapper.builder().build();
    HttpServer server;
    final AtomicReference<String> auth = new AtomicReference<>();
    String userEvents;
    String adminEvents;

    @BeforeEach
    void start() throws Exception {
        long now = Instant.now().toEpochMilli();
        // newest first, like Keycloak; the last one is older than the look-back window
        userEvents = "[" + String.join(",",
                "{\"id\":\"e4\",\"time\":" + (now - 1_000) + ",\"type\":\"CLIENT_LOGIN\",\"clientId\":\"svc-deposit-service\","
                        + "\"userId\":\"sa-1\",\"ipAddress\":\"10.0.0.7\",\"details\":{\"grant_type\":\"client_credentials\","
                        + "\"username\":\"service-account-svc-deposit-service\",\"token_id\":\"t\"}}",
                "{\"id\":\"e3\",\"time\":" + (now - 2_000) + ",\"type\":\"LOGIN_ERROR\",\"clientId\":\"meridian-console\","
                        + "\"userId\":\"u-1\",\"ipAddress\":\"203.0.113.9\",\"error\":\"invalid_user_credentials\","
                        + "\"details\":{\"username\":\"ops.admin\",\"auth_method\":\"openid-connect\"}}",
                "{\"id\":\"e2\",\"time\":" + (now - 3_000) + ",\"type\":\"LOGIN\",\"clientId\":\"meridian-customer-web\","
                        + "\"userId\":\"u-2\",\"sessionId\":\"s-2\",\"details\":{\"redirectUri\":\"http://x\"}}",
                "{\"id\":\"e1\",\"time\":" + (now - 5L * 24 * 3600 * 1000) + ",\"type\":\"LOGIN\",\"userId\":\"u-old\"}") + "]";
        adminEvents = "[{\"id\":\"a1\",\"time\":" + (now - 4_000) + ",\"operationType\":\"CREATE\",\"resourceType\":\"USER\","
                + "\"resourcePath\":\"users/u-9\",\"authDetails\":{\"userId\":\"admin-1\",\"ipAddress\":\"10.1.1.1\"},"
                + "\"representation\":\"{\\\"username\\\":\\\"new.staff\\\",\\\"firstName\\\":\\\"New\\\","
                + "\\\"email\\\":\\\"n@x\\\",\\\"credentials\\\":[{\\\"type\\\":\\\"password\\\",\\\"value\\\":\\\"P@ss\\\"}],"
                + "\\\"attributes\\\":{\\\"tenant_id\\\":[\\\"t\\\"]}}\"}]";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/realms/meridian/protocol/openid-connect/token", ex -> {
            reply(ex, "{\"access_token\":\"tok-1\",\"expires_in\":300}");
        });
        server.createContext("/admin/realms/meridian/events", ex -> {
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            assertThat(ex.getRequestURI().getQuery()).contains("dateFrom=");
            reply(ex, page(userEvents, ex.getRequestURI().getQuery()));
        });
        server.createContext("/admin/realms/meridian/admin-events", ex -> reply(ex, page(adminEvents,
                ex.getRequestURI().getQuery())));
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void recordsEachEventOnceOldestFirstWithoutSecretsOrLoginNames() {
        List<NewAuditEvent> stored = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        KeycloakEventsPoller poller = new KeycloakEventsPoller(json, env(), (tenant, e) -> {
            assertThat(tenant).hasToString(TENANT);
            if (!seen.add(e.sourceEventId())) {
                return false;                                       // AuditRecorder.recordOnce de-duplicates
            }
            return stored.add(e);
        });
        assertThat(poller.pollOnce()).isEqualTo(4);                 // 3 user events in the window + 1 admin event
        assertThat(auth.get()).isEqualTo("Bearer tok-1");
        assertThat(stored).extracting(NewAuditEvent::sourceEventId)
                .containsExactly("kc:e2", "kc:e3", "kc:e4", "kc-admin:a1");
        NewAuditEvent login = stored.get(0);
        assertThat(login.action()).isEqualTo("idp.login");
        assertThat(login.actorType()).isEqualTo("CUSTOMER");
        assertThat(login.outcome()).isEqualTo("SUCCESS");
        assertThat(login.serviceName()).isEqualTo("keycloak");
        assertThat(login.afterState().get("details").has("redirect_uri")).isTrue();     // snake_case keys
        NewAuditEvent failed = stored.get(1);
        assertThat(failed.action()).isEqualTo("idp.login_error");
        assertThat(failed.actorType()).isEqualTo("STAFF_USER");
        assertThat(failed.outcome()).isEqualTo("FAILURE");
        assertThat(failed.reason()).isEqualTo("invalid_user_credentials");
        assertThat(failed.ipAddress()).isEqualTo("203.0.113.9");
        assertThat(failed.afterState().toString()).doesNotContain("ops.admin");         // no login names
        NewAuditEvent service = stored.get(2);
        assertThat(service.actorType()).isEqualTo("SERVICE");
        assertThat(service.actorId()).isEqualTo("svc-deposit-service");
        assertThat(service.afterState().get("details").has("token_id")).isFalse();
        NewAuditEvent admin = stored.get(3);
        assertThat(admin.action()).isEqualTo("idp.admin.create.user");
        assertThat(admin.resourceType()).isEqualTo("idp_user");
        assertThat(admin.actorId()).isEqualTo("admin-1");
        String rep = admin.afterState().get("representation").toString();
        assertThat(rep).contains("first_name").doesNotContain("P@ss").doesNotContain("credentials")
                .doesNotContain("new.staff");

        // the next poll overlaps the window: nothing is recorded twice
        assertThat(poller.pollOnce()).isZero();
        assertThat(stored).hasSize(4);
    }

    @Test
    void pagesBackUntilItPassesTheWindowStart() {
        MockEnvironment env = env().withProperty("meridian.audit.keycloak.page-size", "1");
        List<String> ids = new ArrayList<>();
        new KeycloakEventsPoller(json, env, (tenant, e) -> ids.add(e.sourceEventId())).pollOnce();
        assertThat(ids).containsExactly("kc:e2", "kc:e3", "kc:e4", "kc-admin:a1");
    }

    /** Against a real Keycloak (tools/keycloak_mfa_check.py leaves events there): MERIDIAN_KEYCLOAK_EVENTS_URL. */
    @Test
    void readsARealKeycloakWhenOneIsGiven() {
        String url = System.getenv("MERIDIAN_KEYCLOAK_EVENTS_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "no Keycloak given");
        MockEnvironment env = env().withProperty("meridian.audit.keycloak.base-url", url);
        List<NewAuditEvent> stored = new ArrayList<>();
        new KeycloakEventsPoller(json, env, (tenant, e) -> stored.add(e)).pollOnce();
        assertThat(stored).extracting(NewAuditEvent::action).contains("idp.login", "idp.login_error", "idp.client_login");
        assertThat(stored).anySatisfy(e -> assertThat(e.action()).startsWith("idp.admin."));
        assertThat(stored).allSatisfy(e -> assertThat(e.sourceEventId()).matches("kc(-admin)?:[0-9a-f-]{36}"));
    }

    private MockEnvironment env() {
        return new MockEnvironment()
                .withProperty("meridian.audit.keycloak.base-url", "http://127.0.0.1:" + server.getAddress().getPort())
                .withProperty("meridian.audit.keycloak.client-secret", "meridian-audit-events-secret")
                .withProperty("meridian.audit.keycloak.tenant", TENANT)
                .withProperty("meridian.audit.keycloak.lookback", "2d");
    }

    /** Keycloak paging: first / max over the newest-first list. */
    private String page(String all, String query) {
        var nodes = json.readTree(all);
        int first = Integer.parseInt(param(query, "first", "0"));
        int max = Integer.parseInt(param(query, "max", "100"));
        var out = json.createArrayNode();
        for (int i = first; i < Math.min(nodes.size(), first + max); i++) {
            out.add(nodes.get(i));
        }
        return out.toString();
    }

    private static String param(String query, String name, String fallback) {
        for (String p : query.split("&")) {
            if (p.startsWith(name + "=")) {
                return p.substring(name.length() + 1);
            }
        }
        return fallback;
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }
}
