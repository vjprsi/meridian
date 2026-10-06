package io.meridian.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.identity.application.ApprovalExecutor;
import io.meridian.identity.domain.Base32;
import io.meridian.identity.domain.Secrets;
import io.meridian.identity.domain.Totp;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import io.meridian.web.crud.CrudEngine;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Identity flows over HTTP against PostgreSQL 18 with row-level security. workflow-service is stubbed: approval
 * requests are echoed back PENDING and executed by calling {@link ApprovalExecutor} as the checker would.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.approvals.listener-enabled=false"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IdentityFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000d1";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-0000000000d2";

    @Value("${local.server.port}")
    int port;
    @Autowired
    JsonMapper json;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    CrudEngine crud;
    @Autowired
    ApprovalExecutor approvals;
    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    final AtomicReference<ObjectNode> lastApproval = new AtomicReference<>();
    String alice;
    String bob;
    String permApprove;
    String permRead;
    String permAdmin;
    String teller;
    String totpSecret;
    String totpFactor;

    @BeforeEach
    void stubWorkflow() {
        when(serviceClient.post(eq("workflow-service"), eq("/v1/approval-requests"), any(), anyString()))
                .thenAnswer(inv -> {
                    ObjectNode body = json.valueToTree(inv.getArgument(2)); // the approvals library sends a Map
                    body.put("id", UUID.randomUUID().toString());
                    body.put("status", "PENDING");
                    body.put("maker", TenantContext.principal());
                    body.put("required_approvals", 1);
                    body.put("approvals_received", 0);
                    body.put("current_level", 1);
                    lastApproval.set(body);
                    return body;
                });
    }

    @BeforeAll
    void setUp() throws Exception {
        permApprove = seedPermission("payments.payment.approve", "payments", "payment", "approve");
        permRead = seedPermission("payments.payment.read", "payments", "payment", "read");
        permAdmin = seedPermission("platform.config.admin", "platform", "config", "admin");
        for (String scope : new String[] {"payments:read", "payments:write"}) {
            jdbc.sql("insert into oauth_scope (scope, domain, description) values (?, 'payments', 'test') on conflict do nothing")
                    .param(scope).update();
        }
        alice = post("/v1/users", """
                {"username":"alice","email":"alice@bank.example","first_name":"Alice","last_name":"Ng",
                 "branch_id":"0199a000-0000-7000-8000-00000000b001"}""", 201).get("id").asString();
        bob = post("/v1/users", """
                {"username":"bob","email":"bob@bank.example","first_name":"Bob","last_name":"Ode"}""", 201)
                .get("id").asString();
    }

    @Test
    @Order(1)
    void newUsersAreInvitedAndCreateIsIdempotent() throws Exception {
        JsonNode u = get("/v1/users/" + alice, TENANT);
        assertThat(u.get("status").asString()).isEqualTo("INVITED");
        assertThat(u.hasNonNull("invited_at")).isTrue();
        assertThat(u.has("password_hash")).isFalse();
        String body = """
                {"username":"carol","email":"carol@bank.example","first_name":"Carol","last_name":"K"}""";
        HttpResponse<String> first = send("POST", "/v1/users", body, "user-carol", TENANT, null);
        HttpResponse<String> replay = send("POST", "/v1/users", body, "user-carol", TENANT, null);
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(replay.body()).get("id")).isEqualTo(json.readTree(first.body()).get("id"));
        // validation: missing required fields -> 400; status is read-only (ignored), new users start INVITED
        assertThat(send("POST", "/v1/users", "{\"username\":\"x\"}", key(), TENANT, null).statusCode()).isEqualTo(400);
        JsonNode dave = post("/v1/users", """
                {"username":"dave","email":"dave@bank.example","first_name":"D","last_name":"K","status":"ACTIVE"}""", 201);
        assertThat(dave.get("status").asString()).isEqualTo("INVITED");
        // PATCH cannot change the status (read-only field is ignored)
        HttpResponse<String> patch = send("PATCH", "/v1/users/" + dave.get("id").asString(),
                "{\"status\":\"ACTIVE\",\"job_title\":\"Teller\"}", null, TENANT, "1");
        assertThat(patch.statusCode()).as(patch.body()).isEqualTo(200);
        assertThat(json.readTree(patch.body()).get("status").asString()).isEqualTo("INVITED");
        assertThat(json.readTree(patch.body()).get("job_title").asString()).isEqualTo("Teller");
        // /me resolves the calling principal
        HttpResponse<String> me = send("GET", "/v1/users/me", null, null, TENANT, null, "alice");
        assertThat(json.readTree(me.body()).get("id").asString()).isEqualTo(alice);
        assertThat(send("GET", "/v1/users/me", null, null, TENANT, null, "nobody").statusCode()).isEqualTo(404);
    }

    @Test
    @Order(2)
    void totpEnrolmentActivatesInvitedUser() throws Exception {
        HttpResponse<String> r = send("POST", "/v1/users/" + alice + "/mfa-factors",
                "{\"user_id\":\"" + alice + "\",\"factor_type\":\"TOTP\",\"label\":\"phone\"}", "mfa-alice", TENANT, null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        JsonNode f = json.readTree(r.body());
        totpFactor = f.get("id").asString();
        totpSecret = f.get("totp_secret").asString();
        assertThat(f.get("otpauth_uri").asString()).startsWith("otpauth://totp/Meridian:alice?secret=" + totpSecret);
        assertThat(f.get("status").asString()).isEqualTo("PENDING_VERIFICATION");
        // replay never reveals the secret again; GET never shows it
        JsonNode replay = json.readTree(send("POST", "/v1/users/" + alice + "/mfa-factors",
                "{\"user_id\":\"" + alice + "\",\"factor_type\":\"TOTP\",\"label\":\"phone\"}", "mfa-alice", TENANT, null).body());
        assertThat(replay.has("totp_secret")).isFalse();
        assertThat(get("/v1/users/" + alice + "/mfa-factors/" + totpFactor, TENANT).has("totp_secret")).isFalse();

        String verify = "/v1/users/" + alice + "/mfa-factors/" + totpFactor + "/verify";
        String good = Totp.at(Base32.decode(totpSecret), Instant.now(), 6);
        String wrong = good.equals("000000") ? "111111" : "000000";
        assertThat(post(verify, "{\"code\":\"" + wrong + "\"}", 422).get("code").asString()).isEqualTo("invalid_code");
        JsonNode ok = post(verify, "{\"code\":\"" + good + "\"}", 200);
        assertThat(ok.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(ok.get("is_primary").asBoolean()).isTrue();
        assertThat(ok.has("totp_secret")).isFalse();
        assertThat(outboxCount("platform.mfa_factor.verified", totpFactor)).isEqualTo(1);
        assertThat(post(verify, "{\"code\":\"" + good + "\"}", 409).get("code").asString()).isEqualTo("factor_already_verified");
        JsonNode user = get("/v1/users/" + alice, TENANT);
        assertThat(user.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(user.get("mfa_enrolled").asBoolean()).isTrue();
        // SMS OTP requires a destination
        assertThat(post("/v1/users/" + alice + "/mfa-factors", "{\"user_id\":\"" + alice + "\",\"factor_type\":\"SMS_OTP\"}", 422)
                .get("code").asString()).isEqualTo("destination_required");
    }

    @Test
    @Order(3)
    void lockUnlockStateMachineRevokesSessions() throws Exception {
        String session = seedSession(alice);
        assertThat(post("/v1/users/" + alice + "/invite", null, 409).get("code").asString()).isEqualTo("invalid_transition");
        JsonNode locked = post("/v1/users/" + alice + "/lock", "{\"reason\":\"suspicious activity\"}", 200);
        assertThat(locked.get("status").asString()).isEqualTo("LOCKED");
        assertThat(get("/v1/sessions/" + session, TENANT).hasNonNull("revoked_at")).isTrue();
        assertThat(post("/v1/users/" + alice + "/lock", "{\"reason\":\"again\"}", 409).get("code").asString())
                .isEqualTo("invalid_transition");
        assertThat(post("/v1/users/" + alice + "/unlock", "{\"reason\":\"verified\"}", 200).get("status").asString())
                .isEqualTo("ACTIVE");
        assertThat(send("POST", "/v1/users/" + alice + "/lock", "{}", key(), TENANT, null).statusCode()).isEqualTo(400);
        assertThat(post("/v1/users/" + bob + "/invite", null, 200).get("status").asString()).isEqualTo("INVITED");
        // bob becomes active through MFA enrolment so he can act as checker
        JsonNode f = post("/v1/users/" + bob + "/mfa-factors", "{\"user_id\":\"" + bob + "\",\"factor_type\":\"TOTP\"}", 201);
        post("/v1/users/" + bob + "/mfa-factors/" + f.get("id").asString() + "/verify",
                "{\"code\":\"" + Totp.at(Base32.decode(f.get("totp_secret").asString()), Instant.now(), 6) + "\"}", 200);
    }

    @Test
    @Order(4)
    void permissionGrantIsMakerCheckerAndCloneCopiesGrants() throws Exception {
        teller = post("/v1/roles", "{\"code\":\"TELLER\",\"name\":\"Teller\"}", 201).get("id").asString();
        JsonNode apr = postAs("/v1/roles/" + teller + "/permissions",
                "{\"permission_ids\":[\"" + permApprove + "\",\"" + permRead + "\"]}", 200, "maker1");
        assertThat(apr.get("status").asString()).isEqualTo("PENDING");
        assertThat(apr.get("resource_type").asString()).isEqualTo("role");
        assertThat(apr.get("action").asString()).isEqualTo("add_permissions");
        assertThat(get("/v1/roles/" + teller + "/permissions", TENANT).get("data")).isEmpty();
        ObjectNode approval = lastApproval.get();
        // pending -> not executable; maker cannot check; another user can
        assertThat(exec(approval, "checker1")).isEqualTo("approval_not_approved");
        approval.put("status", "APPROVED");
        assertThat(exec(approval, "maker1")).isEqualTo("four_eyes_violation");
        assertThat(exec(approval, "checker1")).isEqualTo("ok");
        assertThat(exec(approval, "checker1")).isEqualTo("ok"); // idempotent
        assertThat(get("/v1/roles/" + teller + "/permissions", TENANT).get("data")).hasSize(2);
        assertThat(post("/v1/roles/" + teller + "/permissions",
                "{\"permission_ids\":[\"" + permRead + "\"]}", 422).get("code").asString()).isEqualTo("already_granted");
        assertThat(post("/v1/roles/" + teller + "/permissions",
                "{\"permission_ids\":[\"" + UUID.randomUUID() + "\"]}", 422).get("code").asString())
                .isEqualTo("unknown_permission");

        JsonNode clone = post("/v1/roles/" + teller + "/clone", "{\"code\":\"TELLER_2\",\"name\":\"Teller copy\"}", 200);
        assertThat(clone.get("role_type").asString()).isEqualTo("CUSTOM");
        assertThat(get("/v1/roles/" + clone.get("id").asString() + "/permissions", TENANT).get("data")).hasSize(2);
        assertThat(send("POST", "/v1/roles/" + teller + "/clone", "{\"code\":\"TELLER_2\",\"name\":\"dup\"}", key(), TENANT, null)
                .statusCode()).isEqualTo(409);
        // role_type is read-only: a client cannot create a SYSTEM role
        assertThat(post("/v1/roles", "{\"code\":\"SYS\",\"name\":\"Sys\",\"role_type\":\"SYSTEM\"}", 201)
                .get("role_type").asString()).isEqualTo("CUSTOM");
    }

    @Test
    @Order(5)
    void assignmentsSegregationOfDutiesAndEffectivePermissions() throws Exception {
        post("/v1/user-roles", "{\"user_id\":\"" + alice + "\",\"role_id\":\"" + teller + "\"}", 201);
        assertThat(post("/v1/user-roles", "{\"user_id\":\"" + alice + "\",\"role_id\":\"" + teller + "\"}", 409)
                .get("code").asString()).isEqualTo("duplicate_assignment");
        String auditor = post("/v1/roles", "{\"code\":\"AUDITOR\",\"name\":\"Auditor\",\"incompatible_role_ids\":[\""
                + teller + "\"]}", 201).get("id").asString();
        assertThat(post("/v1/user-roles", "{\"user_id\":\"" + alice + "\",\"role_id\":\"" + auditor + "\"}", 422)
                .get("code").asString()).isEqualTo("sod_conflict");
        assertThat(post("/v1/user-roles", "{\"user_id\":\"" + alice + "\",\"role_id\":\"" + teller
                + "\",\"scope_type\":\"BRANCH\"}", 422).get("code").asString()).isEqualTo("invalid_scope");

        JsonNode eff = get("/v1/users/" + alice + "/effective-permissions", TENANT);
        assertThat(eff.get("roles").toString()).contains("TELLER");
        assertThat(eff.get("permissions").toString()).contains("payments.payment.approve", "payments.payment.read");
        assertThat(eff.get("permissions").toString()).doesNotContain("platform.config.admin");
        assertThat(get("/v1/users/" + alice + "/roles", TENANT).get("data")).hasSize(1);
    }

    @Test
    @Order(6)
    void justInTimeElevationForPrivilegedRole() throws Exception {
        String admin = post("/v1/roles", "{\"code\":\"CONFIG_ADMIN\",\"name\":\"Config admin\",\"is_privileged\":true,"
                + "\"max_session_minutes\":60}", 201).get("id").asString();
        post("/v1/roles/" + admin + "/permissions", "{\"permission_ids\":[\"" + permAdmin + "\"]}", 200);
        ObjectNode grant = lastApproval.get();
        grant.put("status", "APPROVED");
        assertThat(exec(grant, "checker1")).isEqualTo("ok");

        assertThat(post("/v1/user-roles", "{\"user_id\":\"" + alice + "\",\"role_id\":\"" + admin + "\"}", 422)
                .get("code").asString()).isEqualTo("privileged_role_requires_elevation");
        assertThat(postAs("/v1/user-roles/elevate", "{\"role_id\":\"" + admin
                + "\",\"duration_minutes\":120,\"justification\":\"incident\"}", 422, "alice").get("code").asString())
                .isEqualTo("invalid_duration");
        JsonNode apr = postAs("/v1/user-roles/elevate", "{\"role_id\":\"" + admin
                + "\",\"duration_minutes\":30,\"justification\":\"INC-42 config fix\"}", 200, "alice");
        assertThat(apr.get("action").asString()).isEqualTo("elevate");
        assertThat(apr.get("maker").asString()).isEqualTo("alice");
        ObjectNode approval = lastApproval.get();
        approval.put("status", "APPROVED");
        assertThat(exec(approval, "alice")).isEqualTo("four_eyes_violation");
        assertThat(exec(approval, "bob")).isEqualTo("ok");
        assertThat(exec(approval, "bob")).isEqualTo("ok");
        JsonNode roles = get("/v1/user-roles?user_id=" + alice + "&role_id=" + admin, TENANT).get("data");
        assertThat(roles).hasSize(1);
        assertThat(roles.get(0).get("approval_request_id").asString()).isEqualTo(approval.get("id").asString());
        OffsetDateTime validTo = OffsetDateTime.parse(roles.get(0).get("valid_to").asString());
        assertThat(validTo).isBetween(OffsetDateTime.now().plusMinutes(25), OffsetDateTime.now().plusMinutes(31));
        assertThat(get("/v1/users/" + alice + "/effective-permissions", TENANT).get("permissions").toString())
                .contains("platform.config.admin");
    }

    @Test
    @Order(7)
    void accessSimulationAppliesAbacAfterRbac() throws Exception {
        post("/v1/access-policies", """
                {"name":"deny-large-approvals","effect":"DENY","priority":10,"resource_pattern":"payments.payment.*",
                 "actions":["approve"],"condition_expression":"resource.amount > 10000"}""", 201);
        post("/v1/access-policies", """
                {"name":"own-branch","effect":"ALLOW","priority":20,"resource_pattern":"payments.*",
                 "actions":["*"],"condition_expression":"subject.branch_id == resource.branch_id"}""", 201);
        assertThat(post("/v1/access-policies", """
                {"name":"broken","effect":"DENY","resource_pattern":"*","actions":["*"],
                 "condition_expression":"resource.amount >"}""", 422).get("code").asString()).isEqualTo("invalid_condition");

        String branch = "0199a000-0000-7000-8000-00000000b001";
        JsonNode small = simulate(alice, "payments.payment.approve", "{\"amount\":500,\"branch_id\":\"" + branch + "\"}");
        assertThat(small.get("decision").asString()).isEqualTo("ALLOW");
        assertThat(small.get("reason").asString()).isEqualTo("policy_allowed");
        JsonNode large = simulate(alice, "payments.payment.approve", "{\"amount\":20000,\"branch_id\":\"" + branch + "\"}");
        assertThat(large.get("decision").asString()).isEqualTo("DENY");
        assertThat(large.get("reason").asString()).isEqualTo("policy_denied");
        JsonNode noPerm = simulate(alice, "cards.card.block", "{}");
        assertThat(noPerm.get("reason").asString()).isEqualTo("no_permission");
        JsonNode inactive = simulate(bob, "payments.payment.approve", "{}");
        assertThat(inactive.get("rbac_granted").asBoolean()).isFalse();
    }

    @Test
    @Order(8)
    void apiClientSecretsAreShownOnceAndStatusMachine() throws Exception {
        assertThat(post("/v1/api-clients", """
                {"name":"bad","client_type":"SERVER","auth_method":"CLIENT_SECRET_BASIC","allowed_scopes":["nope:all"]}""",
                422).get("code").asString()).isEqualTo("unknown_scope");
        assertThat(post("/v1/api-clients", """
                {"name":"jwt","client_type":"SERVER","allowed_scopes":["payments:read"]}""",
                422).get("code").asString()).isEqualTo("jwks_uri_required");
        JsonNode c = post("/v1/api-clients", """
                {"name":"erp","client_type":"SERVER","auth_method":"CLIENT_SECRET_BASIC","environment":"PRODUCTION",
                 "allowed_scopes":["payments:read","payments:write"]}""", 201);
        String id = c.get("id").asString();
        assertThat(c.get("client_id").asString()).startsWith("cli_");
        String secret = c.get("client_secret").asString();
        assertThat(secret).startsWith("cs_");
        assertThat(storedHash("api_client", "client_secret_hash", id)).isEqualTo(Secrets.sha256Hex(secret));
        assertThat(get("/v1/api-clients/" + id, TENANT).has("client_secret")).isFalse();

        JsonNode rotated = post("/v1/api-clients/" + id + "/rotate-secret", null, 200);
        assertThat(rotated.get("client_secret").asString()).isNotEqualTo(secret);
        assertThat(rotated.get("client_id").asString()).isEqualTo(c.get("client_id").asString());
        assertThat(outboxCount("platform.api_client.secret_rotated", id)).isEqualTo(1);
        assertThat(storedHash("api_client", "client_secret_hash", id)).isEqualTo(Secrets.sha256Hex(rotated.get("client_secret").asString()));

        assertThat(post("/v1/api-clients/" + id + "/suspend", "{\"reason\":\"breach\"}", 200).get("status").asString())
                .isEqualTo("SUSPENDED");
        assertThat(post("/v1/api-clients/" + id + "/suspend", "{\"reason\":\"breach\"}", 409).get("code").asString())
                .isEqualTo("invalid_transition");
        assertThat(post("/v1/api-clients/" + id + "/rotate-secret", null, 409).get("code").asString())
                .isEqualTo("invalid_transition");
        assertThat(post("/v1/api-clients/" + id + "/reactivate", null, 200).get("status").asString()).isEqualTo("ACTIVE");

        TenantContext.callAs(UUID.fromString(TENANT), "gateway", () -> {
            for (int i = 0; i < 4; i++) {
                ObjectNode log = json.createObjectNode();
                log.put("api_client_id", id);
                log.put("request_id", "req-" + i);
                log.put("method", "GET");
                log.put("path", "/v1/payments");
                log.put("operation_id", "paymentsList");
                log.put("status_code", i == 0 ? 500 : 200);
                log.put("latency_ms", 10 * (i + 1));
                log.put("environment", "PRODUCTION");
                log.put("occurred_at", OffsetDateTime.now().minusHours(1).toString());
                crud.insert("api_request_log", log);
            }
            return null;
        });
        JsonNode usage = get("/v1/api-clients/" + id + "/usage", TENANT);
        assertThat(usage.get("request_count").asLong()).isEqualTo(4);
        assertThat(usage.get("error_count").asLong()).isEqualTo(1);
        assertThat(usage.get("error_rate").asString()).isEqualTo("0.2500");
        assertThat(get("/v1/api-clients/" + id + "/usage?from=2020-01-01T00:00:00Z&to=2020-02-01T00:00:00Z", TENANT).get("request_count").asLong())
                .isZero();
        assertThat(send("GET", "/v1/api-clients/" + id + "/usage?from=yesterday", null, null, TENANT, null).statusCode())
                .isEqualTo(400);

        // API keys
        assertThat(post("/v1/api-clients/" + id + "/keys", "{\"api_client_id\":\"" + id
                + "\",\"name\":\"k\",\"scopes\":[\"cards:write\"]}", 422).get("code").asString())
                .isEqualTo("scope_not_allowed");
        JsonNode k = post("/v1/api-clients/" + id + "/keys", "{\"api_client_id\":\"" + id
                + "\",\"name\":\"batch\",\"scopes\":[\"payments:read\"]}", 201);
        String keyId = k.get("id").asString();
        String plain = k.get("api_key").asString();
        assertThat(plain).startsWith(k.get("key_prefix").asString() + ".");
        assertThat(k.get("key_prefix").asString()).startsWith("mk_live_").hasSize(16);
        assertThat(storedHash("api_key", "key_hash", keyId)).isEqualTo(Secrets.sha256Hex(plain));
        JsonNode rot = post("/v1/api-clients/" + id + "/keys/" + keyId + "/rotate", "{\"grace_period_hours\":2}", 200);
        assertThat(rot.get("rotated_from_id").asString()).isEqualTo(keyId);
        assertThat(rot.get("api_key").asString()).isNotEqualTo(plain);
        assertThat(get("/v1/api-clients/" + id + "/keys/" + keyId, TENANT).get("status").asString()).isEqualTo("ROTATING");
        assertThat(get("/v1/api-clients/" + id + "/keys/" + keyId, TENANT).has("api_key")).isFalse();
        assertThat(outboxCount("platform.api_key.rotated", keyId)).isEqualTo(1);
        assertThat(post("/v1/api-clients/" + id + "/keys/" + keyId + "/rotate", "{}", 409).get("code").asString())
                .isEqualTo("invalid_transition");
        assertThat(post("/v1/api-clients/" + id + "/keys/" + keyId + "/revoke", "{\"reason\":\"done\"}", 200)
                .get("status").asString()).isEqualTo("REVOKED");
        assertThat(post("/v1/api-clients/" + id + "/keys/" + keyId + "/revoke", "{\"reason\":\"done\"}", 409)
                .get("code").asString()).isEqualTo("invalid_transition");
    }

    @Test
    @Order(9)
    void ssoProviderTestChecksConfiguration() throws Exception {
        JsonNode entra = post("/v1/sso-providers", """
                {"name":"Entra","protocol":"OIDC","issuer_url":"https://login.example.com/tenant/v2.0","client_id":"abc",
                 "client_secret_ref":"vault://identity/sso/entra"}""", 201);
        String good = entra.get("id").asString();
        assertThat(entra.get("client_secret_ref").asString()).isEqualTo("vault://identity/sso/entra");
        JsonNode ok = post("/v1/sso-providers/" + good + "/test", null, 200);
        assertThat(ok.get("success").asBoolean()).isTrue();
        assertThat(ok.get("checks").toString()).contains("client secret vault reference configured");
        String bad = post("/v1/sso-providers", """
                {"name":"Legacy","protocol":"SAML2","issuer_url":"http://idp.example.com"}""", 201).get("id").asString();
        JsonNode fail = post("/v1/sso-providers/" + bad + "/test", null, 200);
        assertThat(fail.get("success").asBoolean()).isFalse();
        assertThat(fail.get("checks").toString()).contains("signing_certificate", "https");
    }

    @Test
    @Order(10)
    void mfaResetAndSessionRevocation() throws Exception {
        JsonNode apr = postAs("/v1/users/" + alice + "/reset-mfa", "{\"reason\":\"lost phone\"}", 200, "helpdesk");
        assertThat(apr.get("action").asString()).isEqualTo("reset_mfa");
        String s1 = seedSession(alice);
        ObjectNode approval = lastApproval.get();
        approval.put("status", "APPROVED");
        assertThat(exec(approval, "security-officer")).isEqualTo("ok");
        assertThat(get("/v1/users/" + alice + "/mfa-factors/" + totpFactor, TENANT).get("status").asString())
                .isEqualTo("DISABLED");
        assertThat(get("/v1/users/" + alice, TENANT).get("mfa_enrolled").asBoolean()).isFalse();
        assertThat(outboxCount("platform.app_user.mfa_reset", alice)).isEqualTo(1);
        assertThat(get("/v1/sessions/" + s1, TENANT).hasNonNull("revoked_at")).isTrue();
        assertThat(post("/v1/users/" + alice + "/reset-mfa", "{\"reason\":\"again\"}", 422).get("code").asString())
                .isEqualTo("no_mfa_factors");

        String s2 = seedSession(bob);
        String s3 = seedSession(bob);
        assertThat(post("/v1/sessions/" + s2 + "/revoke", "{\"reason\":\"logout\"}", 200).hasNonNull("revoked_at")).isTrue();
        assertThat(post("/v1/sessions/" + s2 + "/revoke", "{\"reason\":\"logout\"}", 409).get("code").asString())
                .isEqualTo("session_already_revoked");
        JsonNode all = post("/v1/sessions/revoke-all", "{\"user_id\":\"" + bob + "\",\"reason\":\"offboarding\"}", 200);
        assertThat(all.get("revoked_count").asInt()).isEqualTo(1);
        assertThat(get("/v1/sessions/" + s3, TENANT).hasNonNull("revoked_at")).isTrue();
    }

    @Test
    @Order(11)
    void deactivationIsTerminalAndEndsAssignments() throws Exception {
        String s = seedSession(alice);
        JsonNode d = post("/v1/users/" + alice + "/deactivate", "{\"reason\":\"left the bank\"}", 200);
        assertThat(d.get("status").asString()).isEqualTo("DEACTIVATED");
        assertThat(get("/v1/sessions/" + s, TENANT).hasNonNull("revoked_at")).isTrue();
        assertThat(post("/v1/users/" + alice + "/unlock", "{\"reason\":\"x\"}", 409).get("code").asString())
                .isEqualTo("invalid_transition");
        assertThat(post("/v1/users/" + alice + "/deactivate", "{\"reason\":\"x\"}", 409).get("code").asString())
                .isEqualTo("invalid_transition");
        JsonNode eff = get("/v1/users/" + alice + "/effective-permissions", TENANT);
        assertThat(eff.get("permissions")).isEmpty();
        assertThat(post("/v1/user-roles", "{\"user_id\":\"" + alice + "\",\"role_id\":\"" + teller + "\"}", 409)
                .get("code").asString()).isEqualTo("user_deactivated");
        long outbox = TenantContext.callAs(UUID.fromString(TENANT), "t", () -> jdbc.sql(
                "select count(*) from outbox where event_type = 'platform.app_user.status_changed' and aggregate_id = ?::uuid")
                .param(alice).query(Long.class).single());
        assertThat(outbox).isGreaterThanOrEqualTo(4);
    }

    @Test
    @Order(12)
    void otherTenantSeesNothing() throws Exception {
        assertThat(send("GET", "/v1/users/" + bob, null, null, OTHER_TENANT, null).statusCode()).isEqualTo(404);
        assertThat(get("/v1/users", OTHER_TENANT).get("data")).isEmpty();
        assertThat(get("/v1/roles", OTHER_TENANT).get("data")).isEmpty();
        assertThat(send("POST", "/v1/users/" + bob + "/lock", "{\"reason\":\"x\"}", key(), OTHER_TENANT, null).statusCode())
                .isEqualTo(404);
        // the permission catalogue is global
        assertThat(get("/v1/permissions", OTHER_TENANT).get("data").size()).isGreaterThanOrEqualTo(3);
        assertThat(send("GET", "/v1/users", null, null, null, null).statusCode()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ helpers

    private long outboxCount(String eventType, String aggregateId) throws Exception {
        return TenantContext.callAs(UUID.fromString(TENANT), "t", () -> jdbc.sql(
                "select count(*) from outbox where event_type = ? and aggregate_id = ?::uuid")
                .params(eventType, aggregateId).query(Long.class).single());
    }

    private String exec(ObjectNode approval, String principal) throws Exception {
        try {
            TenantContext.callAs(UUID.fromString(TENANT), principal, () -> approvals.applyApproved(approval.deepCopy()));
            return "ok";
        } catch (io.meridian.common.error.ApiException e) {
            return e.code();
        }
    }

    private JsonNode simulate(String user, String action, String resource) throws Exception {
        return post("/v1/access-policies/simulate", "{\"user_id\":\"" + user + "\",\"action\":\"" + action
                + "\",\"resource\":" + resource + "}", 200);
    }

    private String seedPermission(String code, String domain, String resource, String action) {
        jdbc.sql("insert into permission (code, domain, resource, action) values (?, ?, ?, ?) on conflict (code) do nothing")
                .params(code, domain, resource, action).update();
        return jdbc.sql("select id::text from permission where code = ?").param(code).query(String.class).single();
    }

    private String seedSession(String user) throws Exception {
        return TenantContext.callAs(UUID.fromString(TENANT), "idp", () -> {
            ObjectNode s = json.createObjectNode();
            s.put("user_id", user);
            s.put("auth_method", "PASSWORD");
            s.put("expires_at", OffsetDateTime.now().plusHours(8).toString());
            return json.readTree(crud.insert("user_session", s)).get("id").asString();
        });
    }

    private String storedHash(String table, String column, String id) throws Exception {
        return TenantContext.callAs(UUID.fromString(TENANT), "t", () -> jdbc.sql(
                "select " + column + " from " + table + " where id = ?::uuid").param(id).query(String.class).single());
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private JsonNode post(String path, String body, int expected) throws Exception {
        return postAs(path, body, expected, null);
    }

    private JsonNode postAs(String path, String body, int expected, String user) throws Exception {
        HttpResponse<String> r = send("POST", path, body, key(), TENANT, null, user);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path, String tenant) throws Exception {
        HttpResponse<String> r = send("GET", path, null, null, tenant, null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String tenant,
                                      String ifMatch) throws Exception {
        return send(method, path, body, idemKey, tenant, ifMatch, null);
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String tenant,
                                      String ifMatch, String user) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        if (tenant != null) {
            b.header("X-Tenant-Id", tenant);
        }
        if (idemKey != null) {
            b.header("Idempotency-Key", idemKey);
        }
        if (ifMatch != null) {
            b.header("If-Match", ifMatch);
        }
        if (user != null) {
            b.header("X-User", user);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
