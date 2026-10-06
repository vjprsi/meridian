package io.meridian.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.tenant.application.ModuleService;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.UUID;
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
 * Tenant-service flows over HTTP against PostgreSQL 18 with RLS: cells and tenant lifecycle, template provisioning,
 * migration, maker-checker modules (workflow-service stubbed), legal entities and branches, settings and flags,
 * branding preview, idempotent replay and tenant isolation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.approvals.listener-enabled=false"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TenantFlowIT {
    static final String OPERATOR = "0199b000-0000-7000-8000-00000000000a";
    static final String OTHER = "0199b000-0000-7000-8000-00000000000b";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ModuleService moduleService;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    String cell1;
    String cell2;
    String bank;
    String legalEntity;

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
                    return body;
                });
    }

    @BeforeAll
    void setUp() throws Exception {
        cell1 = post("/v1/platform/cells", """
                {"code":"aws-euw1-c01","region":"eu-west-1","cloud_provider":"AWS","status":"ACTIVE","capacity_tenants":2}""",
                201, OPERATOR).get("id").asString();
        cell2 = post("/v1/platform/cells", """
                {"code":"aws-euw1-c02","region":"eu-west-1","cloud_provider":"AWS","capacity_tenants":10}""",
                201, OPERATOR).get("id").asString();
    }

    @Test
    @Order(1)
    void cellRulesAndTemplateProvisioning() throws Exception {
        JsonNode badCell = post("/v1/platform/cells", """
                {"code":"bad","region":"eu-west-1","cloud_provider":"AWS","status":"DRAINING"}""", 422, OPERATOR);
        assertThat(badCell.get("code").asString()).isEqualTo("invalid_initial_status");

        JsonNode unknown = post("/v1/platform/tenants/provision-from-template", provisionBody("NOPE", "acme"), 422, OPERATOR);
        assertThat(unknown.get("code").asString()).isEqualTo("unknown_template");

        HttpResponse<String> first = send("POST", "/v1/platform/tenants/provision-from-template",
                provisionBody("RETAIL_BANK", "acme"), "prov-acme", OPERATOR, null);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        JsonNode t = json.readTree(first.body());
        bank = t.get("id").asString();
        assertThat(t.get("status").asString()).isEqualTo("PROVISIONING");
        assertThat(t.get("cell_id").asString()).isEqualTo(cell1);
        assertThat(t.get("home_region").asString()).isEqualTo("eu-west-1");
        assertThat(t.get("template_code").asString()).isEqualTo("RETAIL_BANK");
        assertThat(t.has("metadata")).isFalse();

        HttpResponse<String> replay = send("POST", "/v1/platform/tenants/provision-from-template",
                provisionBody("RETAIL_BANK", "acme"), "prov-acme", OPERATOR, null);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(replay.body()).get("id").asString()).isEqualTo(bank);

        JsonNode dup = post("/v1/platform/tenants/provision-from-template", provisionBody("RETAIL_BANK", "acme"), 409, OPERATOR);
        assertThat(dup.get("code").asString()).isEqualTo("duplicate");

        // seeded rows live in the new tenant's own RLS scope
        JsonNode modules = get("/v1/modules?limit=50", bank).get("data");
        assertThat(modules).hasSize(11);
        assertThat(module(modules, "LEDGER").get("is_enabled").asBoolean()).isTrue();
        assertThat(module(modules, "AI").get("is_enabled").asBoolean()).isFalse();
        assertThat(get("/v1/settings?setting_key=security.session_timeout", bank).get("data")).hasSize(1);
        assertThat(get("/v1/modules", OPERATOR).get("data")).isEmpty();

        assertThat(get("/v1/platform/tenants/" + bank + "/placements", OPERATOR).get("data")).hasSize(1);
        assertThat(get("/v1/platform/cells/" + cell1, OPERATOR).get("tenant_count").asInt()).isEqualTo(1);
        assertThat(outbox("platform.tenant.provisioned", bank)).isEqualTo(1);
    }

    @Test
    @Order(2)
    void tenantLifecycleAndCapacity() throws Exception {
        JsonNode plain = post("/v1/platform/tenants", tenantBody("beta", cell1, null), 201, OPERATOR);
        assertThat(get("/v1/platform/cells/" + cell1, OPERATOR).get("tenant_count").asInt()).isEqualTo(2);
        JsonNode full = post("/v1/platform/tenants", tenantBody("gamma", cell1, null), 422, OPERATOR);
        assertThat(full.get("code").asString()).isEqualTo("cell_capacity_exhausted");
        // status is read-only: a client-sent status is ignored and the tenant starts PROVISIONING
        JsonNode active = post("/v1/platform/tenants", tenantBody("delta", null, "ACTIVE"), 201, OPERATOR);
        assertThat(active.get("status").asString()).isEqualTo("PROVISIONING");
        JsonNode unplaced = post("/v1/platform/tenants", tenantBody("epsilon", null, null), 201, OPERATOR);
        assertThat(post("/v1/platform/tenants/" + unplaced.get("id").asString() + "/activate", null, 422, OPERATOR)
                .get("code").asString()).isEqualTo("tenant_not_placed");
        assertThat(plain.get("status").asString()).isEqualTo("PROVISIONING");

        JsonNode activated = post("/v1/platform/tenants/" + bank + "/activate", null, 200, OPERATOR);
        assertThat(activated.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(activated.hasNonNull("activated_at")).isTrue();
        assertThat(post("/v1/platform/tenants/" + bank + "/activate", null, 409, OPERATOR).get("code").asString())
                .isEqualTo("invalid_transition");
        assertThat(post("/v1/platform/tenants/" + bank + "/reinstate", "{\"reason\":\"x\"}", 409, OPERATOR)
                .get("code").asString()).isEqualTo("invalid_transition");
        assertThat(post("/v1/platform/tenants/" + bank + "/suspend", "{}", 400, OPERATOR).get("code").asString())
                .isEqualTo("validation_failed");

        JsonNode suspended = post("/v1/platform/tenants/" + bank + "/suspend", "{\"reason\":\"unpaid invoices\"}", 200, OPERATOR);
        assertThat(suspended.get("status").asString()).isEqualTo("SUSPENDED");
        assertThat(suspended.get("suspension_reason").asString()).isEqualTo("unpaid invoices");
        post("/v1/platform/tenants/" + bank + "/suspend", "{\"reason\":\"again\"}", 409, OPERATOR);
        JsonNode reinstated = post("/v1/platform/tenants/" + bank + "/reinstate", "{\"reason\":\"paid\"}", 200, OPERATOR);
        assertThat(reinstated.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(reinstated.get("reinstatement_reason").asString()).isEqualTo("paid");
        assertThat(reinstated.hasNonNull("reinstated_at")).isTrue();
        assertThat(reinstated.has("suspension_reason")).isFalse();
        assertThat(outbox("platform.tenant.activated", bank)).isEqualTo(1);
        assertThat(outbox("platform.tenant.reinstated", bank)).isEqualTo(1);
        assertThat(outbox("platform.tenant.suspended", bank)).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from outbox where aggregate_id = ?::uuid and (jsonb_exists(payload::jsonb, 'reinstate_reason') or jsonb_exists(payload::jsonb, 'reinstated'))")
                .param(bank).query(Long.class).single()).isZero();

        // status is read-only: PATCHing it is ignored, lifecycle goes through the actions
        HttpResponse<String> patch = send("PATCH", "/v1/platform/tenants/" + bank,
                "{\"status\":\"TERMINATED\",\"billing_email\":\"billing@acme.example\"}", null,
                OPERATOR, reinstated.get("row_version").asString());
        assertThat(patch.statusCode()).as(patch.body()).isEqualTo(200);
        assertThat(json.readTree(patch.body()).get("status").asString()).isEqualTo("ACTIVE");
    }

    @Test
    @Order(3)
    void migrationAndDraining() throws Exception {
        JsonNode notActive = post("/v1/platform/tenants/" + bank + "/migrate", "{\"cell_id\":\"" + cell2 + "\"}", 422, OPERATOR);
        assertThat(notActive.get("code").asString()).isEqualTo("cell_not_accepting_tenants");
        JsonNode c2 = get("/v1/platform/cells/" + cell2, OPERATOR);
        HttpResponse<String> activate = send("PATCH", "/v1/platform/cells/" + cell2, "{\"status\":\"ACTIVE\"}", null,
                OPERATOR, c2.get("row_version").asString());
        assertThat(activate.statusCode()).as(activate.body()).isEqualTo(200);

        JsonNode placement = post("/v1/platform/tenants/" + bank + "/migrate", "{\"cell_id\":\"" + cell2 + "\"}", 200, OPERATOR);
        assertThat(placement.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(placement.get("cell_id").asString()).isEqualTo(cell2);
        assertThat(placement.get("from_cell_id").asString()).isEqualTo(cell1);
        assertThat(get("/v1/platform/tenants/" + bank, OPERATOR).get("cell_id").asString()).isEqualTo(cell2);
        JsonNode history = get("/v1/platform/tenants/" + bank + "/placements", OPERATOR).get("data");
        assertThat(history).hasSize(2);
        java.util.List<String> statuses = new java.util.ArrayList<>();
        history.forEach(h -> statuses.add(h.get("status").asString()));
        assertThat(statuses).containsExactlyInAnyOrder("ACTIVE", "COMPLETED");
        assertThat(get("/v1/platform/cells/" + cell1, OPERATOR).get("tenant_count").asInt()).isEqualTo(1);
        assertThat(get("/v1/platform/cells/" + cell2, OPERATOR).get("tenant_count").asInt()).isEqualTo(1);
        assertThat(post("/v1/platform/tenants/" + bank + "/migrate", "{\"cell_id\":\"" + cell2 + "\"}", 422, OPERATOR)
                .get("code").asString()).isEqualTo("same_cell");
        assertThat(jdbc.sql("select count(*) from outbox where event_type = 'platform.tenant.migrated' and payload->>'subject_tenant_id' = ?")
                .param(bank).query(Long.class).single()).isEqualTo(1L);
        assertThat(send("GET", "/v1/platform/tenants/" + UUID.randomUUID() + "/placements", null, null, OPERATOR, null)
                .statusCode()).isEqualTo(404);

        JsonNode drained = post("/v1/platform/cells/" + cell1 + "/drain", "{\"reason\":\"kernel upgrade\"}", 200, OPERATOR);
        assertThat(drained.get("status").asString()).isEqualTo("DRAINING");
        assertThat(drained.get("drain_reason").asString()).isEqualTo("kernel upgrade");
        assertThat(drained.hasNonNull("drain_requested_at")).isTrue();
        assertThat(drained.has("metadata")).isFalse();
        assertThat(outbox("platform.cell.drain_started", cell1)).isEqualTo(1);
        assertThat(post("/v1/platform/cells/" + cell1 + "/drain", "{\"reason\":\"again\"}", 409, OPERATOR)
                .get("code").asString()).isEqualTo("invalid_transition");
        assertThat(post("/v1/platform/tenants/" + bank + "/migrate", "{\"cell_id\":\"" + cell1 + "\"}", 422, OPERATOR)
                .get("code").asString()).isEqualTo("cell_not_accepting_tenants");
    }

    @Test
    @Order(4)
    void modulesAreMakerChecker() throws Exception {
        JsonNode modules = get("/v1/modules?limit=50", bank).get("data");
        String ai = module(modules, "AI").get("id").asString();
        String analytics = module(modules, "ANALYTICS").get("id").asString();
        String ledger = module(modules, "LEDGER").get("id").asString();

        assertThat(post("/v1/modules/" + ai + "/enable", "{}", 422, bank).get("code").asString())
                .isEqualTo("module_dependency_missing");
        assertThat(post("/v1/modules/" + ledger + "/disable", "{\"reason\":\"cost\"}", 422, bank).get("code").asString())
                .isEqualTo("module_has_dependents");
        assertThat(post("/v1/modules/" + ledger + "/enable", "{}", 409, bank).get("code").asString())
                .isEqualTo("invalid_transition");

        HttpResponse<String> r = send("POST", "/v1/modules/" + analytics + "/enable", "{\"config\":{\"retention_days\":90}}",
                "enable-analytics", bank, null, "maker-1");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode approval = json.readTree(r.body());
        assertThat(approval.get("status").asString()).isEqualTo("PENDING");
        assertThat(approval.get("resource_type").asString()).isEqualTo("tenant_module");
        assertThat(approval.get("action").asString()).isEqualTo("enable");
        assertThat(module(get("/v1/modules?limit=50", bank).get("data"), "ANALYTICS").get("is_enabled").asBoolean()).isFalse();

        JsonNode current = get("/v1/modules/" + analytics, bank);
        // is_enabled is read-only: PATCHing it is ignored (enable/disable is maker-checker)
        HttpResponse<String> patch = send("PATCH", "/v1/modules/" + analytics,
                "{\"is_enabled\":true,\"config\":{\"retention_days\":30}}", null, bank, current.get("row_version").asString());
        assertThat(patch.statusCode()).as(patch.body()).isEqualTo(200);
        assertThat(json.readTree(patch.body()).get("is_enabled").asBoolean()).isFalse();

        ObjectNode pending = (ObjectNode) approval.deepCopy();
        assertThatThrownBy(() -> applyAs(bank, "checker-1", pending))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("approval_not_granted"));
        ObjectNode approved = (ObjectNode) approval.deepCopy();
        approved.put("status", "APPROVED");
        assertThatThrownBy(() -> applyAs(bank, "maker-1", approved))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("four_eyes_violation"));
        applyAs(bank, "checker-1", approved);
        applyAs(bank, "checker-1", approved); // re-delivery is a no-op
        JsonNode enabled = get("/v1/modules/" + analytics, bank);
        assertThat(enabled.get("is_enabled").asBoolean()).isTrue();
        assertThat(enabled.get("config").get("retention_days").asInt()).isEqualTo(90);
        assertThat(enabled.get("approval_request_id").asString()).isEqualTo(approval.get("id").asString());
        assertThat(jdbc.sql("select count(*) from outbox where event_type = 'platform.tenant_module.changed' and aggregate_id = ?::uuid")
                .param(analytics).query(Long.class).single()).isEqualTo(1L);
        // AI now has its prerequisite
        assertThat(post("/v1/modules/" + ai + "/enable", "{}", 200, bank).get("status").asString()).isEqualTo("PENDING");
    }

    @Test
    @Order(5)
    void legalEntitiesAndBranches() throws Exception {
        assertThat(post("/v1/legal-entities", legalEntityBody("BAD", "5493001KJTIIGC8Y1R13"), 422, bank)
                .get("code").asString()).isEqualTo("invalid_lei");
        JsonNode le = post("/v1/legal-entities", legalEntityBody("ACME-KE", "5493001KJTIIGC8Y1R12"), 201, bank);
        legalEntity = le.get("id").asString();
        assertThat(outbox("platform.legal_entity.created", legalEntity)).isEqualTo(1);

        JsonNode head = post("/v1/branches", branchBody("HQ", null), 201, bank);
        String headId = head.get("id").asString();
        JsonNode child = post("/v1/branches", branchBody("WESTLANDS", headId), 201, bank);
        String childId = child.get("id").asString();
        assertThat(outbox("platform.branch.created", childId)).isEqualTo(1);

        assertThat(post("/v1/branches/" + headId + "/close", closeBody(LocalDate.now()), 422, bank).get("code").asString())
                .isEqualTo("branch_has_open_children");
        assertThat(post("/v1/branches/" + childId + "/close", "{\"reason\":\"no date\"}", 400, bank).get("code").asString())
                .isEqualTo("validation_failed");
        JsonNode closed = post("/v1/branches/" + childId + "/close", closeBody(LocalDate.now()), 200, bank);
        assertThat(closed.get("status").asString()).isEqualTo("CLOSED");
        assertThat(closed.get("closed_on").asString()).isEqualTo(LocalDate.now().toString());
        assertThat(closed.get("close_reason").asString()).isEqualTo("consolidation");
        assertThat(outbox("platform.branch.closed", childId)).isEqualTo(1);
        assertThat(post("/v1/branches/" + childId + "/close", closeBody(LocalDate.now()), 409, bank).get("code").asString())
                .isEqualTo("invalid_transition");
        HttpResponse<String> patchClosed = send("PATCH", "/v1/branches/" + childId, "{\"name\":\"Reopened\"}", null, bank,
                closed.get("row_version").asString());
        assertThat(patchClosed.statusCode()).isEqualTo(409);
        HttpResponse<String> patchStatus = send("PATCH", "/v1/branches/" + headId, "{\"status\":\"CLOSED\"}", null, bank,
                head.get("row_version").asString());
        assertThat(json.readTree(patchStatus.body()).get("code").asString()).isEqualTo("status_change_requires_action");
        JsonNode closedHead = post("/v1/branches/" + headId + "/close", closeBody(LocalDate.now()), 200, bank);
        assertThat(closedHead.get("status").asString()).isEqualTo("CLOSED");
        assertThat(outbox("platform.branch.updated", headId)).isZero();
        assertThat(outbox("platform.branch.closed", headId)).isEqualTo(1);
    }

    @Test
    @Order(6)
    void settingsFlagsAndBranding() throws Exception {
        assertThat(post("/v1/settings", """
                {"setting_key":"limits.daily_max","value":{"value":"abc"},"value_type":"NUMBER"}""", 422, bank)
                .get("code").asString()).isEqualTo("invalid_setting_value");
        JsonNode s = post("/v1/settings", """
                {"setting_key":"limits.daily_max","value":{"value":5000},"value_type":"NUMBER","is_sensitive":true}""", 201, bank);
        assertThat(jdbc.sql("select payload->>'value' from outbox where event_type = 'platform.tenant_setting.changed' and aggregate_id = ?::uuid")
                .param(s.get("id").asString()).query(String.class).single()).isEqualTo("***");
        HttpResponse<String> del = send("DELETE", "/v1/settings/" + s.get("id").asString(), null, null, bank, null);
        assertThat(del.statusCode()).as(del.body()).isEqualTo(204);
        assertThat(outbox("platform.tenant_setting.deleted", s.get("id").asString())).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from outbox where aggregate_id = ?::uuid and jsonb_exists(payload::jsonb, 'change_type')")
                .param(s.get("id").asString()).query(Long.class).single()).isZero();

        assertThat(post("/v1/feature-flags", """
                {"flag_key":"bad-rollout","is_enabled":true,"rollout_percentage":150}""", 422, bank)
                .get("code").asString()).isEqualTo("invalid_rollout_percentage");
        post("/v1/feature-flags", """
                {"flag_key":"new-dashboard","is_enabled":true,"rollout_percentage":0,
                 "targeting_rules":[{"attribute":"legal_entity_id","operator":"eq","value":"%s","variant":"v2"}]}"""
                .formatted(legalEntity), 201, bank);
        JsonNode match = post("/v1/feature-flags/evaluate", """
                {"subject_type":"staff_user","subject_id":"u-1","context":{"legal_entity_id":"%s"}}""".formatted(legalEntity),
                200, bank);
        assertThat(match.get("flags").get("new-dashboard").get("enabled").asBoolean()).isTrue();
        assertThat(match.get("flags").get("new-dashboard").get("variant").asString()).isEqualTo("v2");
        assertThat(match.get("flags").get("cards.virtual_cards").get("enabled").asBoolean()).isTrue();
        JsonNode miss = post("/v1/feature-flags/evaluate", """
                {"subject_type":"staff_user","subject_id":"u-2","context":{"legal_entity_id":"other"}}""", 200, bank);
        assertThat(miss.get("flags").get("new-dashboard").get("reason").asString()).isEqualTo("DEFAULT");
        assertThat(miss.get("flags").get("ai.assistant").get("reason").asString()).isEqualTo("DISABLED");

        assertThat(post("/v1/branding", """
                {"app_name":"Acme","primary_color":"blue"}""", 422, bank).get("code").asString()).isEqualTo("invalid_color");
        JsonNode b = post("/v1/branding", """
                {"app_name":"Acme Mobile","primary_color":"#FFE000","channel":"MOBILE","supported_locales":["en","sw"],
                 "default_locale":"sw"}""", 201, bank);
        JsonNode preview = get("/v1/branding/" + b.get("id").asString() + "/preview", bank);
        assertThat(preview.get("tokens").get("color.primary").asString()).isEqualTo("#FFE000");
        assertThat(preview.get("tokens").get("color.on_primary").asString()).isEqualTo("#000000");
        assertThat(preview.get("screens")).hasSize(4);
        assertThat(preview.get("accessibility").get("wcag_aa").asBoolean()).isTrue();
    }

    @Test
    @Order(7)
    void otherTenantSeesNothing() throws Exception {
        assertThat(send("GET", "/v1/legal-entities/" + legalEntity, null, null, OTHER, null).statusCode()).isEqualTo(404);
        assertThat(get("/v1/settings", OTHER).get("data")).isEmpty();
        assertThat(get("/v1/feature-flags", OTHER).get("data")).isEmpty();
        JsonNode eval = post("/v1/feature-flags/evaluate", "{\"subject_type\":\"x\",\"subject_id\":\"y\"}", 200, OTHER);
        assertThat(eval.get("flags").isEmpty()).isTrue();
        assertThat(send("GET", "/v1/settings", null, null, null, null).statusCode()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ helpers

    private void applyAs(String tenant, String principal, JsonNode approval) throws Exception {
        TenantContext.callAs(UUID.fromString(tenant), principal, () -> moduleService.applyApproved(approval));
    }

    private long outbox(String type, String aggregateId) {
        return jdbc.sql("select count(*) from outbox where event_type = ? and aggregate_id = ?::uuid")
                .params(type, aggregateId).query(Long.class).single();
    }

    private static JsonNode module(JsonNode modules, String name) {
        for (JsonNode m : modules) {
            if (name.equals(m.get("module").asString())) {
                return m;
            }
        }
        throw new AssertionError("no module " + name);
    }

    private static String provisionBody(String template, String code) {
        return """
                {"template_code":"%s","code":"%s","name":"%s Bank","legal_name":"%s Bank Ltd","tenant_type":"BANK",
                 "default_currency":"KES","default_country":"KE","primary_contact_email":"ops@%s.example"}"""
                .formatted(template, code, code, code, code);
    }

    private static String tenantBody(String code, String cell, String status) {
        return """
                {"code":"%s","name":"%s","legal_name":"%s Ltd","tenant_type":"FINTECH","home_region":"eu-west-1",
                 "default_currency":"EUR","default_country":"IE","primary_contact_email":"ops@%s.example"%s%s}"""
                .formatted(code, code, code, code, cell == null ? "" : ",\"cell_id\":\"" + cell + "\"",
                        status == null ? "" : ",\"status\":\"" + status + "\"");
    }

    private static String legalEntityBody(String code, String lei) {
        return """
                {"code":"%s","legal_name":"Acme Bank Kenya Ltd","country":"KE","base_currency":"KES","lei":"%s",
                 "swift_bic":"ACMEKENA","timezone":"Africa/Nairobi","fiscal_year_start_month":1}""".formatted(code, lei);
    }

    private String branchBody(String code, String parent) {
        return """
                {"legal_entity_id":"%s","code":"%s","name":"%s","country":"KE","city":"Nairobi","opened_on":"2020-01-01"%s}"""
                .formatted(legalEntity, code, code, parent == null ? "" : ",\"parent_id\":\"" + parent + "\"");
    }

    private static String closeBody(LocalDate d) {
        return "{\"closed_on\":\"" + d + "\",\"reason\":\"consolidation\"}";
    }

    private JsonNode post(String path, String body, int expected, String tenant) throws Exception {
        HttpResponse<String> r = send("POST", path, body, UUID.randomUUID().toString(), tenant, null);
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
        return send(method, path, body, idemKey, tenant, ifMatch, "ops-user");
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String tenant,
                                      String ifMatch, String user) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").header("X-User", user);
        if (tenant != null) {
            b.header("X-Tenant-Id", tenant);
        }
        if (idemKey != null) {
            b.header("Idempotency-Key", idemKey);
        }
        if (ifMatch != null) {
            b.header("If-Match", "\"" + ifMatch + "\"");
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
