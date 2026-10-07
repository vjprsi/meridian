package io.meridian.agentchannel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real security chain (JWT mode, decoder mocked): only agent tokens (scope channels:agent + agent_id claim) reach
 * /v1/agent; the agent and the authentication time come from the token, never from X-Agent-Id / X-Auth-Time; staff
 * (even admin / wildcard), service and customer tokens are refused; step-up uses the token's auth_time.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=jwt"})
@Import(MeridianTestContainers.class)
class AgentChannelJwtIT {
    static final String TENANT = "0199a000-0000-7000-8000-00000000a702";
    static final String A = "0199a000-0000-7000-8000-0000000a10a1";
    static final String B = "0199a000-0000-7000-8000-0000000a10b1";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @MockitoBean
    JwtDecoder decoder;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    FakeNetwork net;

    @BeforeEach
    void setUp() {
        net = new FakeNetwork(json);
        for (String a : new String[] {A, B}) {
            String wallet = UUID.randomUUID().toString();
            net.add("agent-network-service", "/v1/agents", """
                    {"id":"%s","agent_code":"X","agent_level":"AGENT","legal_name":"Agent","contact_msisdn":"+254722000000",
                     "currency":"KES","status":"ACTIVE","float_wallet_id":"%s"}""".formatted(a, wallet));
            net.add("wallet-service", "/v1/wallets", """
                    {"id":"%s","wallet_number":"F","wallet_type":"FLOAT","agent_id":"%s","display_name":"Float",
                     "currency":"KES","status":"ACTIVE","kyc_tier":3,"is_default":true,"has_pin":false}"""
                    .formatted(wallet, a));
        }
        net.wire(serviceClient);
        when(decoder.decode(anyString())).thenAnswer(i -> token(i.getArgument(0)));
    }

    /** Token values: agent-fresh, agent-stale, staff-admin, service, customer. */
    private static Jwt token(String value) {
        Jwt.Builder b = Jwt.withTokenValue(value).header("alg", "RS256").subject("kc-" + value)
                .claim("tenant_id", TENANT).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300));
        return switch (value) {
            case "agent-fresh" -> b.claim("scope", "openid profile channels:agent").claim("agent_id", A)
                    .claim("auth_time", Instant.now().minusSeconds(30).getEpochSecond()).build();
            case "agent-stale" -> b.claim("scope", "openid profile channels:agent").claim("agent_id", A)
                    .claim("auth_time", Instant.now().minusSeconds(3600).getEpochSecond()).build();
            case "staff-admin" -> b.claim("scope", "wallets:* channels:* meridian:admin").claim("agent_id", B).build();
            case "service" -> b.claim("scope", "meridian:internal channels:agent").claim("agent_id", B).build();
            case "customer" -> b.claim("scope", "openid channels:customer").claim("customer_id", B).claim("agent_id", B).build();
            default -> throw new BadJwtException("bad token");
        };
    }

    @Test
    void onlyAgentTokensGetInAndTheAgentComesFromTheToken() throws Exception {
        HttpResponse<String> me = call("agent-fresh", "GET", "/v1/agent/me", null, B);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(json.readTree(me.body()).path("id").asString()).isEqualTo(A);     // X-Agent-Id B ignored
        for (String t : new String[] {"staff-admin", "service", "customer"}) {
            assertThat(call(t, "GET", "/v1/agent/me", null, null).statusCode()).as(t).isEqualTo(403);
        }
        assertThat(call("nonsense", "GET", "/v1/agent/me", null, null).statusCode()).isEqualTo(401);
        assertThat(call(null, "GET", "/v1/agent/me", null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void stepUpUsesTheTokensAuthTime() throws Exception {
        String cashIn = "{\"payee_msisdn\":\"+254700000001\",\"amount\":\"5\"}";
        HttpResponse<String> stale = call("agent-stale", "POST", "/v1/agent/cash-in", cashIn, null);
        assertThat(stale.statusCode()).isEqualTo(401);
        assertThat(json.readTree(stale.body()).path("code").asString()).isEqualTo("step_up_required");
        assertThat(net.writes()).isEmpty();
        HttpResponse<String> fresh = call("agent-fresh", "POST", "/v1/agent/cash-in", cashIn, null);
        assertThat(fresh.statusCode()).isEqualTo(200);
        assertThat(json.readTree(fresh.body()).path("agent_id").asString()).isEqualTo(A);
    }

    private HttpResponse<String> call(String token, String method, String path, String body, String agentHeader)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                // dev-mode headers must be ignored in JWT mode
                .header("X-Auth-Time", String.valueOf(Instant.now().getEpochSecond()));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        if (agentHeader != null) {
            b.header("X-Agent-Id", agentHeader).header("X-Tenant-Id", UUID.randomUUID().toString());
        }
        if (!method.equals("GET")) {
            b.header("Idempotency-Key", UUID.randomUUID().toString());
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
