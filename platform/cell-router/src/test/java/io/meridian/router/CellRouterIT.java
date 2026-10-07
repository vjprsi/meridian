package io.meridian.router;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The router in jwt mode against two (three) fake cell gateways and a fake tenant registry: routing by the verified
 * tenant claim, client tenant headers ignored and stripped, public callbacks routed by tenant_id, frozen tenants
 * (writes 503 + Retry-After, reads served), failover to the standby when the home cell is OFFLINE (and reads only while
 * the standby is an unpromoted READ_ONLY warm standby), placement cache invalidation by event, metrics per cell.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.router.events.enabled=false", "meridian.router.health.enabled=false",
                "meridian.router.cells-refresh=1h", "meridian.router.cache-ttl=1h", "management.server.port=0",
                "meridian.router.retry-after=20s"})
class CellRouterIT {
    static final String OPERATOR = "0199c000-0000-7000-8000-0000000000aa";
    static final FakeEstate estate;

    static {
        try {
            estate = new FakeEstate("cell-a", "cell-b", "cell-c", "cell-s");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("meridian.router.registry-url", estate::registryUrl);
        r.add("meridian.router.operator-tenant", () -> OPERATOR);
        r.add("meridian.router.jwt.jwk-set-uri", () -> estate.registryUrl() + "/jwks");
        r.add("meridian.router.jwt.issuer", () -> FakeEstate.ISSUER);
        r.add("meridian.router.jwt.audiences", () -> FakeEstate.AUDIENCE);
    }

    @AfterAll
    static void stop() {
        estate.close();
    }

    @LocalServerPort
    int port;

    @Autowired
    RegistryEvents events;

    @Autowired
    MeterRegistry meters;

    final HttpClient http = HttpClient.newHttpClient();

    @Test
    void routesByTheVerifiedTenantClaimAndIgnoresClientTenantHeaders() throws Exception {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        estate.place(t1, "cell-a", null, "NORMAL");
        estate.place(t2, "cell-b", null, "NORMAL");

        HttpResponse<String> a = send("GET", "/v1/accounts?limit=1", estate.token(t1), null);
        assertThat(a.statusCode()).as(a.body()).isEqualTo(200);
        assertThat(a.body()).contains("\"cell\":\"cell-a\"").contains("/v1/accounts?limit=1");
        assertThat(a.headers().firstValue("X-Meridian-Cell")).contains("cell-a");
        assertThat(send("GET", "/v1/accounts", estate.token(t2), null).body()).contains("cell-b");

        // t1's token with t2 in the client headers: routed by the claim, and the headers never reach the cell
        HttpResponse<String> spoof = http.send(base("/v1/accounts").header("Authorization", "Bearer " + estate.token(t1))
                .header("X-Tenant-Id", t2.toString()).header("X-User", "root").header("X-Roles", "admin")
                .header("X-Meridian-Service", "ledger-service").header("X-Forwarded-For", "6.6.6.6").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(spoof.body()).contains("cell-a");
        assertThat(estate.lastHeaders.get("cell-a")).doesNotContainKeys("x-tenant-id", "x-user", "x-roles",
                "x-meridian-service");
        assertThat(estate.lastHeaders.get("cell-a").get("x-forwarded-for")).containsExactly("127.0.0.1");
        assertThat(estate.lastHeaders.get("cell-a").get("authorization")).singleElement().asString().startsWith("Bearer ey");
        // the registry is read with the router's own operator identity
        assertThat(estate.registryAuth.get("tenant")).isEqualTo(OPERATOR);
    }

    @Test
    void tokensMustVerifyAndOnlyPublicCallbacksRouteByParameter() throws Exception {
        UUID t = UUID.randomUUID();
        estate.place(t, "cell-b", null, "NORMAL");
        assertThat(send("GET", "/v1/accounts", estate.sign(estate.otherKey, t, FakeEstate.AUDIENCE,
                Instant.now().plusSeconds(60)), null).statusCode()).isEqualTo(401);
        assertThat(send("GET", "/v1/accounts", estate.sign(estate.key, t, "other-api", Instant.now().plusSeconds(60)), null)
                .statusCode()).isEqualTo(401);
        assertThat(send("GET", "/v1/accounts", estate.sign(estate.key, t, FakeEstate.AUDIENCE, Instant.now().minusSeconds(600)),
                null).statusCode()).isEqualTo(401);
        HttpResponse<String> anonymous = http.send(base("/v1/accounts?tenant_id=" + t).header("X-Tenant-Id", t.toString())
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.body()).contains("unauthenticated");

        HttpResponse<String> callback = http.send(base("/v1/interop-transfers/callbacks?tenant_id=" + t)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"x\":1}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(callback.statusCode()).as(callback.body()).isEqualTo(200);
        assertThat(callback.body()).contains("cell-b");
        HttpResponse<String> noTenant = http.send(base("/v1/interop-transfers/callbacks")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(noTenant.statusCode()).isEqualTo(400);

        assertThat(send("GET", "/v1/accounts", estate.token(UUID.randomUUID()), null).statusCode()).isEqualTo(404);
    }

    @Test
    void frozenTenantsKeepReadingButWritesGet503WithRetryAfter() throws Exception {
        UUID t = UUID.randomUUID();
        estate.place(t, "cell-a", null, "READ_ONLY");
        assertThat(send("GET", "/v1/accounts", estate.token(t), null).statusCode()).isEqualTo(200);
        assertThat(send("HEAD", "/v1/accounts", estate.token(t), null).statusCode()).isEqualTo(200);
        HttpResponse<String> write = send("POST", "/v1/accounts", estate.token(t), "{}");
        assertThat(write.statusCode()).isEqualTo(503);
        assertThat(write.headers().firstValue("Retry-After")).contains("20");
        assertThat(write.body()).contains("tenant_read_only");

        UUID blocked = UUID.randomUUID();
        estate.place(blocked, "cell-a", null, "BLOCKED");
        assertThat(send("GET", "/v1/accounts", estate.token(blocked), null).statusCode()).isEqualTo(503);
        assertThat(meters.counter("meridian.router.requests", "cell", "cell-a", "outcome", "tenant_read_only").count())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void failsOverToTheStandbyWhileTheHomeCellIsOfflineAndBack() throws Exception {
        UUID t = UUID.randomUUID();
        UUID warm = UUID.randomUUID();
        UUID alone = UUID.randomUUID();
        estate.place(t, "cell-c", "cell-b", "NORMAL");
        estate.place(warm, "cell-c", "cell-s", "NORMAL");
        estate.place(alone, "cell-c", null, "NORMAL");
        estate.cellStatus.put("cell-s", "READ_ONLY");
        events.apply("platform.cell.status_changed", OPERATOR, estate.cellJson("cell-s"));
        assertThat(send("POST", "/v1/payments", estate.token(t), "{}").body()).contains("cell-c");

        // an operator marks cell-c OFFLINE: the event reaches the router, the tenants move to their standby at once
        estate.cellStatus.put("cell-c", "OFFLINE");
        events.apply("platform.cell.status_changed", OPERATOR, estate.cellJson("cell-c"));
        HttpResponse<String> failover = send("POST", "/v1/payments", estate.token(t), "{}");
        assertThat(failover.statusCode()).isEqualTo(200);
        assertThat(failover.body()).contains("cell-b");
        assertThat(failover.headers().firstValue("X-Meridian-Cell")).contains("cell-b");
        assertThat(meters.counter("meridian.router.failover.requests", "from_cell", "cell-c", "to_cell", "cell-b").count())
                .isEqualTo(1);

        // warm standby not yet promoted (READ_ONLY): reads fail over, writes wait
        assertThat(send("GET", "/v1/payments", estate.token(warm), null).body()).contains("cell-s");
        HttpResponse<String> unpromoted = send("POST", "/v1/payments", estate.token(warm), "{}");
        assertThat(unpromoted.statusCode()).isEqualTo(503);
        assertThat(unpromoted.body()).contains("cell_read_only");
        assertThat(unpromoted.headers().firstValue("Retry-After")).isPresent();
        // promoted (database failover done, operator brings the standby online): writes flow
        estate.cellStatus.put("cell-s", "ACTIVE");
        events.apply("platform.cell.status_changed", OPERATOR, estate.cellJson("cell-s"));
        assertThat(send("POST", "/v1/payments", estate.token(warm), "{}").body()).contains("cell-s");

        HttpResponse<String> none = send("GET", "/v1/payments", estate.token(alone), null);
        assertThat(none.statusCode()).isEqualTo(503);
        assertThat(none.body()).contains("cell_unavailable");

        // failback
        estate.cellStatus.put("cell-c", "ACTIVE");
        events.apply("platform.cell.status_changed", OPERATOR, estate.cellJson("cell-c"));
        assertThat(send("POST", "/v1/payments", estate.token(t), "{}").body()).contains("cell-c");
    }

    @Test
    void placementIsCachedUntilAnInvalidationEvent() throws Exception {
        UUID t = UUID.randomUUID();
        estate.place(t, "cell-a", null, "NORMAL");
        assertThat(send("GET", "/v1/accounts", estate.token(t), null).body()).contains("cell-a");
        int lookups = estate.routingLookups.get();
        estate.place(t, "cell-b", null, "NORMAL");           // switched in the registry (tenant move)
        assertThat(send("GET", "/v1/accounts", estate.token(t), null).body()).contains("cell-a");
        assertThat(estate.routingLookups.get()).isEqualTo(lookups);   // served from the cache

        events.apply("platform.tenant.migrated", t.toString(), "{\"subject_tenant_id\":\"" + t + "\",\"cell_id\":\"x\"}");
        assertThat(send("GET", "/v1/accounts", estate.token(t), null).body()).contains("cell-b");
        assertThat(estate.routingLookups.get()).isEqualTo(lookups + 1);

        // a freeze arrives as a tenant event too
        estate.place(t, "cell-b", null, "READ_ONLY");
        events.apply("platform.tenant.traffic_mode_changed", t.toString(), "{\"id\":\"" + t + "\",\"code\":\"x\"}");
        assertThat(send("POST", "/v1/accounts", estate.token(t), "{}").statusCode()).isEqualTo(503);
    }

    /** GAP-10: request and upstream latency histograms on the management port (J6), nothing on the routed port. */
    @Test
    void prometheusOnTheManagementPortHasLatencyHistograms() throws Exception {
        UUID t = UUID.randomUUID();
        estate.place(t, "cell-a", null, "NORMAL");
        assertThat(send("GET", "/v1/accounts", estate.token(t), null).statusCode()).isEqualTo(200);
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + managementPort + "/actuator/prometheus")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("http_server_requests_seconds_bucket")
                .contains("meridian_router_upstream_seconds_bucket");
    }

    @org.springframework.beans.factory.annotation.Value("${local.management.port}")
    int managementPort;

    private HttpRequest.Builder base(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    }

    private HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder b = base(path).header("Authorization", "Bearer " + token);
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
