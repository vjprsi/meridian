package io.meridian.businesschannel;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real security chain (JWT mode, decoder mocked): only business portal tokens reach /v1/business, the business and
 * the user come from the token's claims (never from the X-Business-* headers), staff, service, customer and agent
 * tokens are refused, and step-up uses the token's auth_time. In JWT mode the first sign-in bootstraps nobody: bank
 * staff (channels:admin) provision the first ADMIN, whose id the tokens then carry. Machine-to-machine tokens (m2m
 * marker, azp = the API client) act as the business's TECHNICAL user of that client only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=jwt", "meridian.business-date.source=clock"})
@Import(MeridianTestContainers.class)
class BusinessChannelJwtIT {
    static final String TENANT = "0199a000-0000-7000-8000-00000000b502";
    static final String A = "0199a000-0000-7000-8000-0000000b10a2";
    static final String B = "0199a000-0000-7000-8000-0000000b10b2";
    static final String ADMIN_A = "0199a000-0000-7000-8000-0000000b1a02";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @MockitoBean
    JwtDecoder decoder;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    FakeBank bank;
    String accountA;
    String accountB;
    /** ADMIN of A, provisioned by staff once for the class (the database outlives each test instance). */
    static volatile String adminA;

    @BeforeEach
    void setUp() throws Exception {
        bank = new FakeBank(json);
        for (String c : new String[] {A, B}) {
            bank.add("customer-service", "/v1/customers",
                    "{\"id\":\"%s\",\"customer_type\":\"COMPANY\",\"status\":\"ACTIVE\",\"display_name\":\"C\"}".formatted(c));
        }
        accountA = bank.add("deposit-service", "/v1/deposit-accounts",
                "{\"customer_id\":\"%s\",\"currency\":\"KES\",\"status\":\"ACTIVE\"}".formatted(A)).path("id").asString();
        accountB = bank.add("deposit-service", "/v1/deposit-accounts",
                "{\"customer_id\":\"%s\",\"currency\":\"KES\",\"status\":\"ACTIVE\"}".formatted(B)).path("id").asString();
        bank.wire(serviceClient);
        when(decoder.decode(anyString())).thenAnswer(i -> token(i.getArgument(0)));
        if (adminA == null) {
            HttpResponse<String> r = call("staff", "POST", "/v1/business-provisioning/admins",
                    "{\"business_customer_id\":\"%s\",\"display_name\":\"Ada Admin\",\"email\":\"ada@a.test\"}"
                            .formatted(A), null);
            assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
            adminA = json.readTree(r.body()).path("id").asString();
        }
    }

    /**
     * Token values: business-fresh, business-stale, business-weak (no auth_time), business-unknown (a user nobody
     * provisioned), staff, channels-admin, service, customer, agent, and m2m~&lt;user&gt;~&lt;client&gt; /
     * m2m-stale~... (client credentials with the m2m marker, issued now / 10 minutes ago).
     */
    private static Jwt token(String value) {
        Jwt.Builder b = Jwt.withTokenValue(value).header("alg", "RS256").subject("kc-" + value)
                .claim("tenant_id", TENANT).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300));
        String biz = "openid profile channels:business";
        String admin = adminA == null ? ADMIN_A : adminA;
        if (value.startsWith("m2m~") || value.startsWith("m2m-stale~")) {
            String[] p = value.split("~");
            Instant iat = value.startsWith("m2m-stale~") ? Instant.now().minusSeconds(600) : Instant.now();
            return Jwt.withTokenValue(value).header("alg", "RS256").subject("service-account-" + p[2])
                    .claim("tenant_id", TENANT).issuedAt(iat).expiresAt(Instant.now().plusSeconds(300))
                    .claim("scope", "channels:business").claim("business_customer_id", A).claim("business_user_id", p[1])
                    .claim("m2m", true).claim("azp", p[2]).build();
        }
        return switch (value) {
            case "business-unknown" -> b.claim("scope", biz).claim("business_customer_id", A)
                    .claim("business_user_id", ADMIN_A).claim("auth_time", Instant.now().getEpochSecond()).build();
            case "channels-admin" -> b.claim("scope", "openid channels:admin").build();
            case "business-fresh" -> b.claim("scope", biz).claim("business_customer_id", A).claim("business_user_id", admin)
                    .claim("auth_time", Instant.now().minusSeconds(30).getEpochSecond()).build();
            case "business-stale" -> b.claim("scope", biz).claim("business_customer_id", A).claim("business_user_id", admin)
                    .claim("auth_time", Instant.now().minusSeconds(3600).getEpochSecond()).build();
            case "business-weak" -> b.claim("scope", biz).claim("business_customer_id", A).claim("business_user_id", admin)
                    .build();
            case "staff" -> b.claim("scope", "accounts:* payments:* channels:* meridian:admin")
                    .claim("business_customer_id", B).claim("business_user_id", ADMIN_A).build();
            case "service" -> b.claim("scope", "meridian:internal channels:business")
                    .claim("business_customer_id", B).claim("business_user_id", ADMIN_A).build();
            case "customer" -> b.claim("scope", "openid channels:customer").claim("customer_id", A).build();
            case "agent" -> b.claim("scope", "openid channels:agent").claim("agent_id", A).build();
            default -> throw new BadJwtException("bad token");
        };
    }

    @Test
    void onlyBusinessTokensGetInAndTheBusinessComesFromTheToken() throws Exception {
        HttpResponse<String> mine = call("business-fresh", "GET", "/v1/business/accounts", null, B);
        assertThat(mine.statusCode()).as(mine.body()).isEqualTo(200);
        JsonNode page = json.readTree(mine.body());
        assertThat(page.path("data")).hasSize(1);
        assertThat(page.path("data").get(0).path("customer_id").asString()).isEqualTo(A);   // header B ignored
        assertThat(call("business-fresh", "GET", "/v1/business/accounts/" + accountB, null, B).statusCode()).isEqualTo(404);

        for (String other : new String[] {"staff", "service", "customer", "agent"}) {
            assertThat(call(other, "GET", "/v1/business/accounts", null, null).statusCode()).as(other).isEqualTo(403);
        }
        assertThat(call("nonsense", "GET", "/v1/business/accounts", null, null).statusCode()).isEqualTo(401);
        assertThat(call(null, "GET", "/v1/business/accounts", null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void stepUpUsesTheTokensAuthTime() throws Exception {
        String user = "{\"display_name\":\"Mo\",\"email\":\"mo@a.test\",\"role\":\"MAKER\"}";
        for (String t : new String[] {"business-stale", "business-weak"}) {
            HttpResponse<String> r = call(t, "POST", "/v1/business/users", user, null);
            assertThat(r.statusCode()).as(t).isEqualTo(401);
            assertThat(json.readTree(r.body()).path("code").asString()).isEqualTo("step_up_required");
            assertThat(r.headers().firstValue("WWW-Authenticate").orElse("")).contains("insufficient_user_authentication");
        }
        HttpResponse<String> ok = call("business-fresh", "POST", "/v1/business/users", user, null);
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
        assertThat(json.readTree(ok.body()).path("business_customer_id").asString()).isEqualTo(A);
    }

    @Test
    void theFirstAdminIsProvisionedByStaffNotBootstrappedFromTheToken() throws Exception {
        // bootstrap is off in JWT mode: an unprovisioned user of a business without users gets nothing
        HttpResponse<String> unknown = call("business-unknown", "GET", "/v1/business/me", null, null);
        assertThat(unknown.statusCode()).as(unknown.body()).isEqualTo(403);
        assertThat(json.readTree(unknown.body()).path("code").asString()).isEqualTo("business_user_unknown");
        HttpResponse<String> me = call("business-fresh", "GET", "/v1/business/me", null, null);
        assertThat(me.statusCode()).as(me.body()).isEqualTo(200);
        assertThat(json.readTree(me.body()).path("user").path("id").asString()).isEqualTo(adminA);
        // staff operation: channels:admin (or meridian:admin); business, customer, agent and service-business tokens never
        String req = "{\"business_customer_id\":\"%s\",\"display_name\":\"X\",\"email\":\"x@b.test\"}".formatted(B);
        for (String t : new String[] {"business-fresh", "customer", "agent"}) {
            HttpResponse<String> r = call(t, "POST", "/v1/business-provisioning/admins", req, null);
            assertThat(r.statusCode()).as(t + " " + r.body()).isEqualTo(403);
        }
        HttpResponse<String> list = call("channels-admin", "GET", "/v1/business-provisioning/users?business_customer_id=" + A,
                null, null);
        assertThat(list.statusCode()).as(list.body()).isEqualTo(200);
        assertThat(json.readTree(list.body()).path("data").toString()).contains(adminA);
        HttpResponse<String> again = call("channels-admin", "POST", "/v1/business-provisioning/admins",
                req.replace(B, A), null);
        assertThat(again.statusCode()).isEqualTo(409);
    }

    @Test
    void machineToMachineTokensActAsTheTechnicalUserOfTheirClient() throws Exception {
        bank.rows("identity-service", "/v1/api-clients");
        bank.rows("payment-service", "/v1/payment-mandates");
        HttpResponse<String> created = call("business-fresh", "POST", "/v1/business/api-clients",
                "{\"name\":\"ERP\",\"technical_role\":\"MAKER\"}", null);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        String client = json.readTree(created.body()).path("client_id").asString();
        String technical = bank.lastWrite("identity-service", "/v1/api-clients").body().path("business_user_id").asString();

        HttpResponse<String> me = call("m2m~" + technical + "~" + client, "GET", "/v1/business/me", null, null);
        assertThat(me.statusCode()).as(me.body()).isEqualTo(200);
        assertThat(json.readTree(me.body()).path("user").path("user_type").asString()).isEqualTo("TECHNICAL");
        // another client's token, or an m2m token naming a human user, is refused
        assertThat(call("m2m~" + technical + "~cli_other", "GET", "/v1/business/me", null, null).statusCode()).isEqualTo(403);
        assertThat(call("m2m~" + adminA + "~" + client, "GET", "/v1/business/me", null, null).statusCode()).isEqualTo(403);
        // its issue time stands for the authentication: a token issued 10 minutes ago fails step-up
        String mandate = "{\"scheme\":\"INTERNAL\",\"creditor_account_id\":\"%s\",\"creditor_id\":\"C1\","
                + "\"mandate_reference\":\"M1\",\"debtor_name\":\"D\",\"debtor_account_number\":\"1\"}";
        HttpResponse<String> ent = call("business-fresh", "PUT", "/v1/business/users/" + technical + "/entitlements/"
                + accountA, "{\"can_view\":true,\"can_initiate\":true,\"can_approve\":false}", null);
        assertThat(ent.statusCode()).as(ent.body()).isEqualTo(200);
        HttpResponse<String> stale = call("m2m-stale~" + technical + "~" + client, "POST", "/v1/business/mandates",
                mandate.formatted(accountA), null);
        assertThat(stale.statusCode()).as(stale.body()).isEqualTo(401);
        HttpResponse<String> fresh = call("m2m~" + technical + "~" + client, "POST", "/v1/business/mandates",
                mandate.formatted(accountA), null);
        assertThat(fresh.statusCode()).as(fresh.body()).isEqualTo(200);
    }

    private HttpResponse<String> call(String token, String method, String path, String body, String businessHeader)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                // dev-mode headers must be ignored in JWT mode
                .header("X-Auth-Time", String.valueOf(Instant.now().getEpochSecond()));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        if (businessHeader != null) {
            b.header("X-Business-Customer-Id", businessHeader).header("X-Business-User-Id", UUID.randomUUID().toString())
                    .header("X-Tenant-Id", UUID.randomUUID().toString());
        }
        if (!method.equals("GET")) {
            b.header("Idempotency-Key", UUID.randomUUID().toString());
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
