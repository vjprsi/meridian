package io.meridian.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.when;

import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 * Config-service flows over HTTP against PostgreSQL 18 with RLS. workflow-service (approval requests) is stubbed:
 * approvals are created PENDING and the test flips them to APPROVED / REJECTED.
 * Principals: alice = maker, bob = checker (X-User header).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConfigFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000c0f01";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-0000000c0f02";
    static final String ALICE = "alice";
    static final String BOB = "bob";
    static final String LE = "0199a000-0000-7000-8000-0000000000e1";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    final Map<String, String> approvals = new ConcurrentHashMap<>();
    final Map<String, String> makers = new ConcurrentHashMap<>();

    String v1;
    String v2;
    String v3;
    String approval1;
    String ke1;
    String ke2;
    String keDraft;
    String ug1;
    String install;

    @BeforeAll
    void seedPacks() {
        ke1 = seedPack("KE-2026.1", "KE", "1.0.0", "RELEASED", """
                {"config":{"payments":{"max_txn_amount":500,"rail":"PESALINK"}},
                 "tax_rules":[{"tax_type":"WITHHOLDING","name":"WHT on interest","rate":"0.15",
                               "applies_to":"INTEREST_PAID","valid_from":"2020-01-01","tax_gl_code":"2400"}]}""");
        ke2 = seedPack("KE-2026.2", "KE", "1.1.0", "RELEASED", """
                {"config":{"payments":{"max_txn_amount":600}},
                 "tax_rules":[{"tax_type":"WITHHOLDING","name":"WHT on interest","rate":"0.10",
                               "applies_to":"INTEREST_PAID","valid_from":"2020-01-01"}]}""");
        keDraft = seedPack("KE-2027.0", "KE", "2.0.0", "DRAFT", "{}");
        ug1 = seedPack("UG-2026.1", "UG", "1.2.0", "RELEASED", "{}");
    }

    /** Stubs are re-registered per test: @MockitoBean mocks are reset after each test method. */
    @BeforeEach
    void stubWorkflow() {
        when(serviceClient.post(eq("workflow-service"), eq("/v1/approval-requests"), any(), anyString()))
                .thenAnswer(inv -> {
                    JsonNode req = json.valueToTree(inv.getArgument(2));
                    ObjectNode a = json.createObjectNode();
                    String id = UUID.randomUUID().toString();
                    a.put("id", id);
                    a.put("resource_type", req.get("resource_type").asString());
                    a.put("resource_id", req.get("resource_id").asString());
                    a.put("action", req.get("action").asString());
                    a.put("title", req.get("title").asString());
                    a.set("payload", req.get("payload"));
                    a.put("status", "PENDING");
                    a.put("maker", ALICE);
                    a.put("required_approvals", 1);
                    a.put("approvals_received", 0);
                    a.put("current_level", 1);
                    a.put("priority", "NORMAL");
                    approvals.put(id, "PENDING");
                    makers.put(id, ALICE);
                    return a;
                });
        when(serviceClient.get(eq("workflow-service"), startsWith("/v1/approval-requests/"))).thenAnswer(inv -> {
            String id = inv.getArgument(1, String.class).substring("/v1/approval-requests/".length());
            ObjectNode a = json.createObjectNode();
            a.put("id", id);
            a.put("status", approvals.getOrDefault(id, "PENDING"));
            a.put("maker", makers.getOrDefault(id, ALICE));
            return a;
        });
    }

    // ------------------------------------------------------------------ config entries

    @Test
    @Order(1)
    void createValidatesSchemaAndStartsAsDraft() throws Exception {
        JsonNode bad = post("/v1/config/entries", entryBody(-5), ALICE, 422);
        assertThat(bad.get("code").asString()).isEqualTo("schema_violation");
        assertThat(bad.get("errors").get(0).get("field").asString()).isEqualTo("$.daily_limit");

        JsonNode badName = post("/v1/config/entries", """
                {"namespace":"Payments!","config_key":"limits","value":{"a":1}}""", ALICE, 422);
        assertThat(badName.get("code").asString()).isEqualTo("invalid_value");

        JsonNode e = post("/v1/config/entries", entryBody(1000), ALICE, 201);
        v1 = e.get("id").asString();
        assertThat(e.get("version").asInt()).isEqualTo(1);
        assertThat(e.get("status").asString()).isEqualTo("DRAFT");

        // read-only fields sent by clients are ignored
        JsonNode ro = post("/v1/config/entries", """
                {"namespace":"misc","config_key":"ro_check","value":{"a":1},"status":"ACTIVE","version":9,
                 "approval_request_id":"%s","promoted_from_environment":"prod","submitted_by":"mallory"}"""
                .formatted(UUID.randomUUID()), ALICE, 201);
        assertThat(ro.get("status").asString()).isEqualTo("DRAFT");
        assertThat(ro.get("version").asInt()).isEqualTo(1);
        assertThat(ro.path("approval_request_id").isNull() || ro.path("approval_request_id").isMissingNode()).isTrue();
        assertThat(ro.path("promoted_from_environment").isNull() || ro.path("promoted_from_environment").isMissingNode()).isTrue();
        assertThat(ro.path("submitted_by").isNull() || ro.path("submitted_by").isMissingNode()).isTrue();

        JsonNode early = post("/v1/config/entries/" + v1 + "/activate", null, BOB, 409);
        assertThat(early.get("code").asString()).isEqualTo("invalid_transition");
    }

    @Test
    @Order(2)
    void submitThenActivateEnforcesApprovalAndFourEyes() throws Exception {
        JsonNode missingReason = post("/v1/config/entries/" + v1 + "/submit", "{}", ALICE, 400);
        assertThat(missingReason.get("code").asString()).isIn("validation_failed", "malformed_request");

        JsonNode approval = post("/v1/config/entries/" + v1 + "/submit", "{\"change_reason\":\"raise limits\"}", ALICE, 200);
        approval1 = approval.get("id").asString();
        assertThat(approval.get("resource_type").asString()).isEqualTo("config_entry");
        assertThat(approval.get("status").asString()).isEqualTo("PENDING");
        JsonNode entry = get("/v1/config/entries/" + v1, TENANT);
        assertThat(entry.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(entry.get("approval_request_id").asString()).isEqualTo(approval1);
        assertThat(entry.get("submitted_by").asString()).isEqualTo(ALICE);
        assertThat(entry.get("submitted_at")).isNotNull();

        JsonNode again = post("/v1/config/entries/" + v1 + "/submit", "{\"change_reason\":\"again\"}", ALICE, 409);
        assertThat(again.get("code").asString()).isEqualTo("invalid_transition");

        JsonNode pending = post("/v1/config/entries/" + v1 + "/activate", null, BOB, 409);
        assertThat(pending.get("code").asString()).isEqualTo("approval_pending");

        approvals.put(approval1, "APPROVED");
        JsonNode self = post("/v1/config/entries/" + v1 + "/activate", null, ALICE, 422);
        assertThat(self.get("code").asString()).isEqualTo("four_eyes_violation");

        JsonNode active = post("/v1/config/entries/" + v1 + "/activate", null, BOB, 200);
        assertThat(active.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(active.get("effective_from")).isNotNull();
        assertThat(outboxCount("platform.config_entry.activated")).isEqualTo(1);
    }

    @Test
    @Order(3)
    void rejectedApprovalCannotBeActivated() throws Exception {
        String draft = post("/v1/config/entries", """
                {"namespace":"onboarding","config_key":"kyc_tier","value":{"tier":2}}""", ALICE, 201).get("id").asString();
        String apr = post("/v1/config/entries/" + draft + "/submit", "{\"change_reason\":\"tier 2\"}", ALICE, 200)
                .get("id").asString();
        approvals.put(apr, "REJECTED");
        JsonNode r = post("/v1/config/entries/" + draft + "/activate", null, BOB, 409);
        assertThat(r.get("code").asString()).isEqualTo("approval_not_granted");

        String sentBack = post("/v1/config/entries", """
                {"namespace":"onboarding","config_key":"kyc_limits","value":{"max":5}}""", ALICE, 201).get("id").asString();
        String apr2 = post("/v1/config/entries/" + sentBack + "/submit", "{\"change_reason\":\"limits\"}", ALICE, 200)
                .get("id").asString();
        approvals.put(apr2, "CHANGES_REQUESTED");
        assertThat(post("/v1/config/entries/" + sentBack + "/activate", null, BOB, 409).get("code").asString())
                .isEqualTo("approval_not_granted");
    }

    @Test
    @Order(4)
    void newVersionSupersedesAndHistoryIsRecorded() throws Exception {
        v2 = activateNew(2000);
        assertThat(get("/v1/config/entries/" + v1, TENANT).get("status").asString()).isEqualTo("SUPERSEDED");
        JsonNode e2 = get("/v1/config/entries/" + v2, TENANT);
        assertThat(e2.get("version").asInt()).isEqualTo(2);

        JsonNode history = get("/v1/config/entries/" + v2 + "/history", TENANT).get("data");
        List<String> types = new ArrayList<>();
        history.forEach(h -> types.add(h.get("change_type").asString() + ":" + h.get("to_version").asInt()));
        assertThat(types).containsExactlyInAnyOrder("CREATED:1", "ACTIVATED:1", "CREATED:2", "ACTIVATED:2");
        JsonNode activated2 = null;
        for (JsonNode h : history) {
            if (h.get("change_type").asString().equals("ACTIVATED") && h.get("to_version").asInt() == 2) {
                activated2 = h;
            }
        }
        assertThat(activated2.get("from_version").asInt()).isEqualTo(1);
        assertThat(activated2.get("approved_by").asString()).isEqualTo(BOB);
        assertThat(activated2.get("diff").get(0).get("path").asString()).isEqualTo("/daily_limit");
    }

    @Test
    @Order(5)
    void rollbackRestoresPreviousValueAsNewVersion() throws Exception {
        JsonNode bad = post("/v1/config/entries/" + v2 + "/rollback", "{\"reason\":\"oops\"}", BOB, 409);
        assertThat(bad.get("code").asString()).isEqualTo("invalid_transition");

        JsonNode rolled = post("/v1/config/entries/" + v1 + "/rollback", "{\"reason\":\"incident 42\"}", BOB, 201);
        v3 = rolled.get("id").asString();
        assertThat(rolled.get("version").asInt()).isEqualTo(3);
        assertThat(rolled.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(rolled.get("value").get("daily_limit").asInt()).isEqualTo(1000);
        assertThat(get("/v1/config/entries/" + v2, TENANT).get("status").asString()).isEqualTo("ROLLED_BACK");
        assertThat(outboxCount("platform.config_entry.rolled_back")).isEqualTo(1);
        JsonNode active = get("/v1/config/entries?namespace=payments&config_key=limits&status=ACTIVE", TENANT).get("data");
        assertThat(active).hasSize(1);
    }

    @Test
    @Order(6)
    void idempotentReplayReturnsStoredResponse() throws Exception {
        String body = """
                {"namespace":"limits","config_key":"atm","value":{"max":300}}""";
        HttpResponse<String> first = send("POST", "/v1/config/entries", body, "cfg-replay-1", TENANT, ALICE);
        assertThat(first.statusCode()).isEqualTo(201);
        HttpResponse<String> replay = send("POST", "/v1/config/entries", body, "cfg-replay-1", TENANT, ALICE);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(replay.body()).get("id").asString()).isEqualTo(json.readTree(first.body()).get("id").asString());
        assertThat(get("/v1/config/entries?namespace=limits&config_key=atm", TENANT).get("data")).hasSize(1);

        HttpResponse<String> reused = send("POST", "/v1/config/entries", body.replace("300", "400"), "cfg-replay-1", TENANT, ALICE);
        assertThat(reused.statusCode()).isEqualTo(422);
        assertThat(json.readTree(reused.body()).get("code").asString()).isEqualTo("idempotency_key_reused");

        HttpResponse<String> noKey = send("POST", "/v1/config/entries", body, null, TENANT, ALICE);
        assertThat(noKey.statusCode()).isEqualTo(400);
    }

    // ------------------------------------------------------------------ country packs + resolve

    @Test
    @Order(10)
    void countryPackInstallUpgradeUninstall() throws Exception {
        JsonNode notReleased = post("/v1/config/country-packs/" + keDraft + "/install",
                "{\"legal_entity_id\":\"" + LE + "\"}", ALICE, 422);
        assertThat(notReleased.get("code").asString()).isEqualTo("pack_not_released");

        JsonNode inst = post("/v1/config/country-packs/" + ke1 + "/install", """
                {"legal_entity_id":"%s","overrides":{"config":{"payments":{"rail":"RTGS"}}}}""".formatted(LE), ALICE, 201);
        install = inst.get("id").asString();
        assertThat(inst.get("status").asString()).isEqualTo("INSTALLED");
        assertThat(inst.get("installed_at")).isNotNull();
        assertThat(outboxCount("platform.country_pack.installed")).isEqualTo(1);
        JsonNode seeded = get("/v1/config/tax-rules?country=KE&legal_entity_id=" + LE, TENANT).get("data");
        assertThat(seeded).hasSize(1);
        assertThat(seeded.get(0).get("country_pack_id").asString()).isEqualTo(ke1);

        JsonNode dup = post("/v1/config/country-packs/" + ke2 + "/install", "{\"legal_entity_id\":\"" + LE + "\"}", ALICE, 409);
        assertThat(dup.get("code").asString()).isEqualTo("already_installed");

        JsonNode resolved = get("/v1/config/entries/resolve?namespace=payments&legal_entity_id=" + LE, TENANT);
        assertThat(resolved.get("values").get("max_txn_amount").asInt()).isEqualTo(500);
        assertThat(resolved.get("sources").get("max_txn_amount").get("source").asString()).isEqualTo("country_pack");
        assertThat(resolved.get("values").get("rail").asString()).isEqualTo("RTGS");
        assertThat(resolved.get("sources").get("rail").get("source").asString()).isEqualTo("country_pack_override");
        assertThat(resolved.get("values").get("limits").get("daily_limit").asInt()).isEqualTo(1000);
        assertThat(resolved.get("sources").get("limits").get("source").asString()).isEqualTo("tenant");
        assertThat(resolved.get("sources").get("limits").get("version").asInt()).isEqualTo(3);
        HttpResponse<String> noNs = send("GET", "/v1/config/entries/resolve", null, null, TENANT, ALICE);
        assertThat(noNs.statusCode()).isEqualTo(400);

        JsonNode wrongCountry = post("/v1/config/installed-country-packs/" + install + "/upgrade",
                "{\"country_pack_id\":\"" + ug1 + "\"}", ALICE, 422);
        assertThat(wrongCountry.get("code").asString()).isEqualTo("country_mismatch");
        JsonNode downgrade = post("/v1/config/installed-country-packs/" + install + "/upgrade",
                "{\"country_pack_id\":\"" + ke1 + "\"}", ALICE, 422);
        assertThat(downgrade.get("code").asString()).isEqualTo("not_an_upgrade");
        JsonNode upgraded = post("/v1/config/installed-country-packs/" + install + "/upgrade",
                "{\"country_pack_id\":\"" + ke2 + "\"}", ALICE, 200);
        assertThat(upgraded.get("country_pack_id").asString()).isEqualTo(ke2);
        assertThat(get("/v1/config/entries/resolve?namespace=payments", TENANT).get("values").get("max_txn_amount").asInt())
                .isEqualTo(600);

        // seeded WHT now comes from the 1.1.0 pack (10%), the 1.0.0 rule is expired
        JsonNode wht = calc("INTEREST_PAID", "1000.00", "KES", null, LE);
        assertThat(wht.get("taxes")).hasSize(1);
        assertThat(new BigDecimal(wht.get("total_tax").asString())).isEqualByComparingTo("100.00");

        JsonNode removed = post("/v1/config/installed-country-packs/" + install + "/uninstall", "{\"reason\":\"exit market\"}", ALICE, 200);
        assertThat(removed.get("status").asString()).isEqualTo("UNINSTALLED");
        assertThat(outboxCount("platform.country_pack.uninstalled")).isEqualTo(1);
        JsonNode twice = post("/v1/config/installed-country-packs/" + install + "/uninstall", "{\"reason\":\"again\"}", ALICE, 409);
        assertThat(twice.get("code").asString()).isEqualTo("invalid_transition");
        assertThat(get("/v1/config/entries/resolve?namespace=payments", TENANT).get("values").has("max_txn_amount")).isFalse();
        assertThat(calc("INTEREST_PAID", "1000.00", "KES", null, LE).get("taxes")).isEmpty();
    }

    // ------------------------------------------------------------------ tax rules

    @Test
    @Order(20)
    void taxRulesValidateAndCalculate() throws Exception {
        JsonNode rate = post("/v1/config/tax-rules", taxRule("VAT", "VAT on fees", "1.5", "FEE", null, null), ALICE, 422);
        assertThat(rate.get("code").asString()).isEqualTo("invalid_rate");
        JsonNode dates = post("/v1/config/tax-rules", """
                {"country":"KE","tax_type":"VAT","name":"x","rate":"0.16","applies_to":"FEE",
                 "valid_from":"2026-01-01","valid_to":"2025-01-01"}""", ALICE, 422);
        assertThat(dates.get("code").asString()).isEqualTo("invalid_validity");

        String vat = post("/v1/config/tax-rules", taxRule("VAT", "VAT on fees", "0.16", "FEE", null, null), ALICE, 201)
                .get("id").asString();
        post("/v1/config/tax-rules", taxRule("WITHHOLDING", "WHT resident", "0.15", "INTEREST_PAID", null, "100.00"), ALICE, 201);
        post("/v1/config/tax-rules", taxRule("WITHHOLDING", "WHT non-resident", "0.20", "INTEREST_PAID", "NON_RESIDENT", null), ALICE, 201);
        assertThat(outboxCount("platform.tax_rate_rule.changed")).isEqualTo(3);

        JsonNode fee = calc("FEE", "100.005", "KES", null, null);
        assertThat(fee.get("total_tax").asString()).isEqualTo("16.00");
        JsonNode resident = calc("INTEREST_PAID", "1000", "KES", null, null);
        assertThat(resident.get("total_tax").asString()).isEqualTo("150.00");
        assertThat(resident.get("net_amount").asString()).isEqualTo("850.00");
        JsonNode nonResident = calc("INTEREST_PAID", "1000", "KES", "NON_RESIDENT", null);
        assertThat(nonResident.get("total_tax").asString()).isEqualTo("200.00");
        JsonNode below = calc("INTEREST_PAID", "50", "KES", null, null);
        assertThat(below.get("total_tax").asString()).isEqualTo("0.00");
        assertThat(below.get("taxes").get(0).get("reason").asString()).isEqualTo("below_threshold");
        JsonNode jpy = calc("FEE", "1001", "JPY", null, null);
        assertThat(jpy.get("total_tax").asString()).isEqualTo("160");

        JsonNode current = get("/v1/config/tax-rules/" + vat, TENANT);
        HttpResponse<String> badUpdate = send("PATCH", "/v1/config/tax-rules/" + vat, "{\"rate\":\"-0.1\"}", null, TENANT, ALICE,
                current.get("row_version").asString());
        assertThat(badUpdate.statusCode()).isEqualTo(422);
        HttpResponse<String> ok = send("PATCH", "/v1/config/tax-rules/" + vat, "{\"rate\":\"0.14\"}", null, TENANT, ALICE,
                current.get("row_version").asString());
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
        assertThat(calc("FEE", "100", "KES", null, null).get("total_tax").asString()).isEqualTo("14.00");
        HttpResponse<String> stale = send("PATCH", "/v1/config/tax-rules/" + vat, "{\"rate\":\"0.13\"}", null, TENANT, ALICE,
                current.get("row_version").asString());
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(send("DELETE", "/v1/config/tax-rules/" + vat, null, null, TENANT, ALICE).statusCode()).isEqualTo(204);
        assertThat(outboxCount("platform.tax_rate_rule.changed")).isEqualTo(5);
    }

    // ------------------------------------------------------------------ custom fields

    @Test
    @Order(30)
    void customFieldDefinitionsAndValueValidation() throws Exception {
        JsonNode enumNoValues = post("/v1/config/custom-fields", """
                {"table_name":"customer","slot":2,"field_key":"risk_band","label":"Risk band","data_type":"ENUM"}""", ALICE, 422);
        assertThat(enumNoValues.get("code").asString()).isEqualTo("invalid_custom_field_definition");
        JsonNode badSlot = post("/v1/config/custom-fields", """
                {"table_name":"customer","slot":6,"field_key":"x","label":"X"}""", ALICE, 422);
        assertThat(badSlot.get("errors").get(0).get("code").asString()).isEqualTo("invalid_slot");
        JsonNode badRegex = post("/v1/config/custom-fields", """
                {"table_name":"customer","slot":1,"field_key":"x","label":"X","validation_regex":"(["}""", ALICE, 422);
        assertThat(badRegex.get("errors").get(0).get("code").asString()).isEqualTo("invalid_regex");

        post("/v1/config/custom-fields", """
                {"table_name":"customer","slot":1,"field_key":"employer_id","label":"Employer","is_required":true,
                 "validation_regex":"^[A-Z0-9]{3,10}$"}""", ALICE, 201);
        post("/v1/config/custom-fields", """
                {"table_name":"customer","slot":2,"field_key":"risk_band","label":"Risk band","data_type":"ENUM",
                 "allowed_values":["LOW","HIGH"],"default_value":"LOW"}""", ALICE, 201);
        post("/v1/config/custom-fields", """
                {"table_name":"customer","slot":3,"field_key":"income","label":"Income","data_type":"DECIMAL",
                 "min_value":"0","max_value":"1000000"}""", ALICE, 201);
        JsonNode dupSlot = post("/v1/config/custom-fields", """
                {"table_name":"customer","slot":3,"field_key":"other","label":"Other"}""", ALICE, 409);
        assertThat(dupSlot.get("code").asString()).isEqualTo("duplicate");
        assertThat(outboxCount("platform.custom_field_definition.changed")).isEqualTo(3);

        JsonNode invalid = post("/v1/config/custom-fields/validate", """
                {"table_name":"customer","values":{"risk_band":"MID","custom_field_3":"-1","nickname":"x"}}""", ALICE, 200);
        assertThat(invalid.get("valid").asBoolean()).isFalse();
        List<String> codes = new ArrayList<>();
        invalid.get("errors").forEach(e -> codes.add(e.get("field").asString() + ":" + e.get("code").asString()));
        assertThat(codes).containsExactlyInAnyOrder("nickname:unknown_field", "employer_id:required",
                "risk_band:not_allowed", "income:below_minimum");

        JsonNode valid = post("/v1/config/custom-fields/validate", """
                {"table_name":"customer","values":{"employer_id":"ACME01","income":"52000.50"}}""", ALICE, 200);
        assertThat(valid.get("valid").asBoolean()).as(valid.toString()).isTrue();
        assertThat(valid.get("normalized").get("custom_field_2").asString()).isEqualTo("LOW");
        assertThat(valid.get("normalized").get("custom_field_1").asString()).isEqualTo("ACME01");
    }

    // ------------------------------------------------------------------ export / import + isolation

    @Test
    @Order(40)
    void exportImportPromotesAsDrafts() throws Exception {
        JsonNode bundle = post("/v1/config/entries/export", "{\"namespaces\":[\"payments\"],\"environment\":\"staging\"}", ALICE, 200);
        assertThat(bundle.get("entry_count").asInt()).isEqualTo(1);
        assertThat(bundle.get("checksum").asString()).hasSize(64);
        assertThat(bundle.get("environment").asString()).isEqualTo("staging");

        ObjectNode tampered = (ObjectNode) bundle.deepCopy();
        ((ObjectNode) tampered.get("entries").get(0).get("value")).put("daily_limit", 999999);
        HttpResponse<String> bad = send("POST", "/v1/config/entries/import",
                "{\"bundle\":" + json.writeValueAsString(tampered) + "}", UUID.randomUUID().toString(), OTHER_TENANT, ALICE);
        assertThat(bad.statusCode()).isEqualTo(422);
        assertThat(json.readTree(bad.body()).get("code").asString()).isEqualTo("checksum_mismatch");

        String importBody = "{\"bundle\":" + json.writeValueAsString(bundle) + "}";
        HttpResponse<String> imported = send("POST", "/v1/config/entries/import", importBody, UUID.randomUUID().toString(),
                OTHER_TENANT, ALICE);
        assertThat(imported.statusCode()).as(imported.body()).isEqualTo(200);
        JsonNode result = json.readTree(imported.body());
        assertThat(result.get("imported").asInt()).isEqualTo(1);
        assertThat(result.get("created").get(0).get("status").asString()).isEqualTo("DRAFT");
        String importedId = result.get("created").get(0).get("id").asString();
        assertThat(json.readTree(send("GET", "/v1/config/entries/" + importedId, null, null, OTHER_TENANT, ALICE).body())
                .get("promoted_from_environment").asString()).isEqualTo("staging");

        JsonNode again = json.readTree(send("POST", "/v1/config/entries/import", importBody, UUID.randomUUID().toString(),
                OTHER_TENANT, ALICE).body());
        assertThat(again.get("imported").asInt()).isZero();
        assertThat(again.get("skipped").asInt()).isEqualTo(1);
        // same tenant: identical to its ACTIVE value
        JsonNode same = post("/v1/config/entries/import", importBody, ALICE, 200);
        assertThat(same.get("skipped_entries").get(0).get("reason").asString()).isEqualTo("identical_to_active");
    }

    @Test
    @Order(50)
    void otherTenantSeesNothing() throws Exception {
        assertThat(send("GET", "/v1/config/entries/" + v1, null, null, OTHER_TENANT, ALICE).statusCode()).isEqualTo(404);
        assertThat(send("POST", "/v1/config/entries/" + v1 + "/rollback", "{\"reason\":\"x\"}", UUID.randomUUID().toString(),
                OTHER_TENANT, BOB).statusCode()).isEqualTo(404);
        assertThat(get("/v1/config/tax-rules", OTHER_TENANT).get("data")).isEmpty();
        assertThat(get("/v1/config/custom-fields", OTHER_TENANT).get("data")).isEmpty();
        JsonNode resolved = get("/v1/config/entries/resolve?namespace=payments", OTHER_TENANT);
        assertThat(resolved.get("values").has("limits")).isFalse(); // imported version is only a DRAFT
        assertThat(get("/v1/config/installed-country-packs", OTHER_TENANT).get("data")).isEmpty();
        // the country pack catalogue is platform-global
        assertThat(get("/v1/config/country-packs?country=KE", OTHER_TENANT).get("data")).hasSize(3);
    }

    // ------------------------------------------------------------------ helpers

    private String activateNew(int dailyLimit) throws Exception {
        String id = post("/v1/config/entries", entryBody(dailyLimit), ALICE, 201).get("id").asString();
        String apr = post("/v1/config/entries/" + id + "/submit", "{\"change_reason\":\"update\"}", ALICE, 200).get("id").asString();
        approvals.put(apr, "APPROVED");
        post("/v1/config/entries/" + id + "/activate", null, BOB, 200);
        return id;
    }

    private static String entryBody(int dailyLimit) {
        return """
                {"namespace":"payments","config_key":"limits","value":{"daily_limit":%d},
                 "value_schema":{"type":"object","required":["daily_limit"],
                   "properties":{"daily_limit":{"type":"number","minimum":0}}}}""".formatted(dailyLimit);
    }

    private static String taxRule(String type, String name, String rate, String appliesTo, String segment, String threshold) {
        return """
                {"country":"KE","tax_type":"%s","name":"%s","rate":"%s","applies_to":"%s","valid_from":"2020-01-01"%s%s}"""
                .formatted(type, name, rate, appliesTo,
                        segment == null ? "" : ",\"customer_segment\":\"" + segment + "\"",
                        threshold == null ? "" : ",\"threshold_amount\":\"" + threshold + "\",\"currency\":\"KES\"");
    }

    private JsonNode calc(String appliesTo, String amount, String ccy, String segment, String le) throws Exception {
        String body = """
                {"country":"KE","applies_to":"%s","amount":"%s","currency":"%s"%s%s}""".formatted(appliesTo, amount, ccy,
                segment == null ? "" : ",\"customer_segment\":\"" + segment + "\"",
                le == null ? "" : ",\"legal_entity_id\":\"" + le + "\"");
        return post("/v1/config/tax-rules/calculate", body, ALICE, 200);
    }

    private String seedPack(String code, String country, String version, String status, String contents) {
        return jdbc.sql("""
                insert into country_pack (code, country, name, version, status, currency, contents, released_at)
                values (?, ?, ?, ?, ?, 'KES', ?::jsonb, case when ? = 'RELEASED' then now() end) returning id::text""")
                .params(code, country, code + " pack", version, status, contents, status)
                .query(String.class).single();
    }

    private int outboxCount(String type) {
        return jdbc.sql("select count(*) from outbox where event_type = ?").param(type).query(Integer.class).single();
    }

    private JsonNode post(String path, String body, String user, int expected) throws Exception {
        HttpResponse<String> r = send("POST", path, body, UUID.randomUUID().toString(), TENANT, user);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path, String tenant) throws Exception {
        HttpResponse<String> r = send("GET", path, null, null, tenant, ALICE);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String tenant, String user)
            throws Exception {
        return send(method, path, body, idemKey, tenant, user, null);
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String tenant, String user,
                                      String ifMatch) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", tenant).header("X-User", user).header("Content-Type", "application/json");
        if (idemKey != null) {
            b.header("Idempotency-Key", idemKey);
        }
        if (ifMatch != null) {
            b.header("If-Match", ifMatch);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
