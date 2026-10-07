package io.meridian.regulatoryreporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.regulatoryreporting.application.RegulatoryPackCatalog;
import io.meridian.regulatoryreporting.domain.RegulatoryPack;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Kenyan regulatory pack end to end over HTTP against PostgreSQL: catalog, dry run, installation (a tenant-defined
 * report with a pack code is left alone), idempotent re-install, four-eyes publication, then capital adequacy,
 * liquidity and large-cash (CTR / STR) runs fed by stateful fakes of gl-service (accounting periods + trial balance,
 * resolved from the period end), liquidity-service, transaction-monitoring-service, case-management-service and
 * config-service (tenant GL mapping override). Checks computed cells, a validation failure surfacing and its fix,
 * drilldown to source records, filing-calendar due dates, upgrades of pack-owned items only and tenant isolation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RegulatoryPackFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000f1";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-0000000000f2";
    static final String MAKER = "maker.ke";
    static final String CHECKER = "checker.ke";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ServiceClient services;

    final HttpClient http = HttpClient.newHttpClient();
    final String le = UUID.randomUUID().toString();
    final Fake fake = new Fake();
    final Map<String, String> definitions = new LinkedHashMap<>();
    String capitalRun;
    String liquidityRun;

    @BeforeAll
    void seed() {
        RegulatoryPack ke = RegulatoryPackCatalog.load(json, RegulatoryPackCatalog.LOCATION).stream()
                .filter(p -> p.code().equals("KE_CBK")).findFirst().orElseThrow();
        JsonNode sample = ke.document().get("sample_data");
        fake.periods.add(json.createObjectNode().put("id", "P-2026-08").put("legal_entity_id", le).put("period_type", "MONTH")
                .put("end_date", "2026-08-31"));
        fake.periods.add(json.createObjectNode().put("id", "P-2026-09").put("legal_entity_id", le).put("period_type", "MONTH")
                .put("end_date", "2026-09-30"));
        fake.trialBalances.put("P-2026-09", (ArrayNode) sample.get("tb").deepCopy());
        for (String kind : List.of("lcr", "nsfr")) {
            ObjectNode row = (ObjectNode) sample.get(kind).get(0).deepCopy();
            row.put("legal_entity_id", le).put("as_of_date", "2026-09-30");
            fake.ratios.computeIfAbsent(kind, k -> new ArrayList<>()).add(row);
        }
        // a broken LCR: the reported ratio (210%) does not recompute from HQLA / net outflows (186.67%)
        fake.ratios.get("lcr").getFirst().put("lcr_ratio", "2.10");
        for (JsonNode t : sample.get("txns")) {
            ObjectNode row = (ObjectNode) t.deepCopy();
            row.put("created_at", "2026-09-15T10:00:00Z");
            fake.monitored.add(row);
        }
        // outside September: never counted
        fake.monitored.add(json.createObjectNode().put("id", "m9").put("transaction_id", "t9").put("customer_id", "c9")
                .put("is_cash", true).put("direction", "CREDIT").put("channel", "BRANCH").put("currency", "KES")
                .put("amount", "9000000.00").put("created_at", "2026-10-02T09:00:00Z"));
        sample.get("filings").forEach(f -> fake.filings.add(((ObjectNode) f.deepCopy()).put("created_at", "2026-09-20T09:00:00Z")));
    }

    @BeforeEach
    void upstream() {
        when(services.get(anyString(), anyString())).thenAnswer(inv -> fake.get(inv.getArgument(0), inv.getArgument(1)));
    }

    @Test
    @Order(1)
    void catalogListsThreeIllustrativePacks() throws Exception {
        JsonNode cat = get("/v1/reporting/pack-installations/catalog", TENANT);
        assertThat(cat.get("count").asInt()).isEqualTo(3);
        assertThat(cat.get("notice").asString()).contains("ILLUSTRATIVE");
        JsonNode ke = find(cat.get("data"), "pack_code", "KE_CBK");
        assertThat(ke.get("country").asString()).isEqualTo("KE");
        assertThat(ke.get("reports")).hasSize(7);
        assertThat(ke.at("/gl_mapping_tables/KE_CBK_GL/config_key").asString()).isEqualTo("gl_mapping.ke_cbk_gl");
        assertThat(find(ke.get("reports"), "code", "KE-FRC-AML1").get("deadline_rule").asString()).isEqualTo("PERIOD_END+7BD");
        assertThat(code(post("/v1/reporting/pack-installations/install", "{\"pack_code\":\"XX_NONE\"}", MAKER, 422)))
                .isEqualTo("unknown_pack");
        assertThat(code(post("/v1/reporting/pack-installations/install", "{\"pack_code\":\"KE_CBK\",\"version\":\"1999.1.1\"}", MAKER, 422)))
                .isEqualTo("unknown_pack_version");
    }

    @Test
    @Order(2)
    void dryRunAndDisabledInstallChangeNothing() throws Exception {
        JsonNode dry = post("/v1/reporting/pack-installations/install", "{\"pack_code\":\"KE_CBK\",\"dry_run\":true}", MAKER, 200);
        assertThat(dry.get("dry_run").asBoolean()).isTrue();
        assertThat(dry.get("created")).hasSize(7);
        JsonNode off = post("/v1/reporting/pack-installations/install", "{\"pack_code\":\"KE_CBK\",\"enabled\":false}", MAKER, 200);
        assertThat(off.get("skipped").asBoolean()).isTrue();
        assertThat(get("/v1/reporting/definitions?country_pack=KE", TENANT).get("data")).isEmpty();
        assertThat(get("/v1/reporting/pack-installations", TENANT).get("data")).isEmpty();
    }

    @Test
    @Order(3)
    void installCreatesPackOwnedDraftsAndLeavesTenantReportsAlone() throws Exception {
        // the tenant already files its own complaints return under the pack's code
        post("/v1/reporting/definitions", """
                {"code":"KE-CBK-CP1","name":"Our complaints return","country_pack":"KE","jurisdiction":"KE","regulator":"Central Bank of Kenya",
                 "report_family":"COMPLAINTS","frequency":"QUARTERLY","format":"CSV"}""", MAKER, 201);
        JsonNode r = post("/v1/reporting/pack-installations/install",
                "{\"pack_code\":\"KE_CBK\",\"legal_entity_id\":\"" + le + "\"}", MAKER, 200);
        assertThat(r.get("created")).hasSize(6);
        assertThat(r.get("skipped")).singleElement().satisfies(s -> {
            assertThat(s.get("code").asString()).isEqualTo("KE-CBK-CP1");
            assertThat(s.get("reason").asString()).isEqualTo("tenant_defined");
        });
        assertThat(r.get("awaiting_publication_count").asInt()).isEqualTo(6);
        assertThat(r.get("notice").asString()).contains("ILLUSTRATIVE");

        for (JsonNode d : get("/v1/reporting/definitions?country_pack=KE&limit=50", TENANT).get("data")) {
            definitions.put(d.get("code").asString(), d.get("id").asString());
            if (d.get("code").asString().equals("KE-CBK-CP1")) {
                assertThat(d.has("pack_code") && !d.get("pack_code").isNull()).isFalse();
                assertThat(d.get("name").asString()).isEqualTo("Our complaints return");
                continue;
            }
            assertThat(d.get("pack_code").asString()).isEqualTo("KE_CBK");
            assertThat(d.get("pack_version").asString()).isEqualTo("2026.10.0");
            assertThat(d.get("status").asString()).isEqualTo("DRAFT");
            assertThat(d.get("holidays")).isNotEmpty();
        }
        JsonNode ca = get("/v1/reporting/definitions/" + definitions.get("KE-CBK-CA1"), TENANT);
        assertThat(ca.get("deadline_rule").asString()).isEqualTo("PERIOD_END+10D");
        assertThat(ca.get("business_day_convention").asString()).isEqualTo("FOLLOWING");
        JsonNode v = get("/v1/reporting/definitions/" + definitions.get("KE-CBK-CA1") + "/versions", TENANT).get("data");
        assertThat(v).singleElement().satisfies(x -> {
            assertThat(x.get("status").asString()).isEqualTo("DRAFT");
            assertThat(x.get("pack_version").asString()).isEqualTo("2026.10.0");
            assertThat(x.get("created_by").asString()).isEqualTo(MAKER);
            assertThat(x.get("data_sources").toString()).contains("gl_mapping").contains("KE_CBK_GL").doesNotContain("\"use\"");
        });
        JsonNode inst = get("/v1/reporting/pack-installations?pack_code=KE_CBK", TENANT).get("data");
        assertThat(inst).singleElement().satisfies(i -> {
            assertThat(i.get("pack_version").asString()).isEqualTo("2026.10.0");
            assertThat(i.get("pending_version_count").asInt()).isEqualTo(6);
            assertThat(i.get("installed_by").asString()).isEqualTo(MAKER);
        });
        assertThat(outboxCount("reporting.regulatory_pack.installed")).isEqualTo(1);
    }

    @Test
    @Order(4)
    void reinstallIsIdempotent() throws Exception {
        JsonNode r = post("/v1/reporting/pack-installations/install", "{\"pack_code\":\"KE_CBK\"}", MAKER, 200);
        assertThat(r.get("created")).isEmpty();
        assertThat(r.get("updated")).isEmpty();
        assertThat(r.get("unchanged")).hasSize(6);
        assertThat(r.get("awaiting_publication")).hasSize(6);
        assertThat(get("/v1/reporting/definitions/" + definitions.get("KE-CBK-LR1") + "/versions", TENANT).get("data")).hasSize(1);
    }

    @Test
    @Order(5)
    void versionsArePublishedUnderFourEyes() throws Exception {
        assertThat(code(post("/v1/reporting/definitions/" + definitions.get("KE-CBK-CA1") + "/publish",
                "{\"definition_version\":1}", MAKER, 422))).isEqualTo("four_eyes_violation");
        for (String c : List.of("KE-CBK-CA1", "KE-CBK-LR1", "KE-FRC-AML1")) {
            JsonNode p = post("/v1/reporting/definitions/" + definitions.get(c) + "/publish",
                    "{\"definition_version\":1,\"comment\":\"validated against the CBK templates\"}", CHECKER, 200);
            assertThat(p.get("status").asString()).isEqualTo("ACTIVE");
        }
    }

    @Test
    @Order(6)
    void capitalAdequacyRunResolvesTheGlPeriodAndDrillsDownToTheTrialBalance() throws Exception {
        JsonNode run = post("/v1/reporting/runs", runBody("KE-CBK-CA1", "2026-09-30", "{}"), MAKER, 201);
        assertThat(run.get("status").asString()).as(run.toString()).isEqualTo("SUCCEEDED");
        capitalRun = run.get("id").asString();
        assertThat(fake.calls).anyMatch(c -> c.startsWith("gl-service /v1/gl/periods?legal_entity_id=" + le + "&period_type=MONTH"));
        assertThat(fake.calls).contains("gl-service /v1/gl/periods/P-2026-09/trial-balance");
        JsonNode cells = get("/v1/reporting/runs/" + capitalRun + "/cells?limit=200", TENANT).get("data");
        assertThat(cellValue(cells, "CA1_R060")).isEqualTo("290000000.00");
        assertThat(cellValue(cells, "CA1_R100")).isEqualTo("360000000.00");
        assertThat(cellValue(cells, "CA1_R290_C020")).isEqualTo("860000000.00");
        assertThat(cellValue(cells, "CA1_R400")).isEqualTo("1647500000.00");
        assertThat(cellValue(cells, "CA1_R500")).isEqualTo("17.60");
        assertThat(cellValue(cells, "CA1_R510")).isEqualTo("21.85");

        JsonNode drill = get("/v1/reporting/runs/" + capitalRun + "/drilldown?cell_ref=CA1_R230_C020", TENANT);
        assertThat(drill.get("source_name").asString()).isEqualTo("tb");
        assertThat(drill.get("source_records")).singleElement().satisfies(rec -> {
            assertThat(rec.get("id").asString()).isEqualTo("tb7");
            assertThat(rec.at("/record/gl_account_code").asString()).isEqualTo("1500");
            assertThat(rec.at("/record/gl_map/line").asString()).isEqualTo("BS_A040");
            assertThat(rec.at("/record/gl_map/risk_weight").asString()).isEqualTo("0.75");
            assertThat(rec.at("/record/gl_map/origin").asString()).isEqualTo("pack");
        });
        assertThat(drill.at("/mapping/filter").asString()).isEqualTo("gl_map.exposure_class = 'RETAIL'");
        JsonNode outputs = get("/v1/reporting/runs/" + capitalRun + "/outputs", TENANT).get("data");
        assertThat(outputs).extracting(o -> o.get("format").asString()).containsExactlyInAnyOrder("CSV", "JSON");
        outputs.forEach(o -> assertThat(o.get("sha256").asString()).hasSize(64));
    }

    @Test
    @Order(7)
    void tenantGlMappingOverrideFromConfigServiceChangesTheRerun() throws Exception {
        // the tenant books its mortgages with retail weights: override account 1530 in config-service
        fake.overrides.set("gl_mapping.ke_cbk_gl", json.readTree("""
                {"rules":[{"line":"BS_A040","code":"1530","attributes":{"exposure_class":"RETAIL","risk_weight":"0.75"}}]}"""));
        JsonNode rerun = post("/v1/reporting/runs/" + capitalRun + "/rerun", "{\"reason\":\"tenant GL mapping\"}", MAKER, 201);
        assertThat(rerun.get("status").asString()).isEqualTo("SUCCEEDED");
        JsonNode cells = get("/v1/reporting/runs/" + rerun.get("id").asString() + "/cells?limit=200", TENANT).get("data");
        assertThat(cellValue(cells, "CA1_R230_C020")).isEqualTo("375000000.00");
        assertThat(cellValue(cells, "CA1_R240_C020")).isEqualTo("0.00");
        assertThat(cellValue(cells, "CA1_R290_C020")).isEqualTo("885000000.00");
        JsonNode drill = get("/v1/reporting/runs/" + rerun.get("id").asString() + "/drilldown?cell_ref=CA1_R230_C020", TENANT);
        assertThat(drill.get("source_records")).anySatisfy(rec -> assertThat(rec.at("/record/gl_map/origin").asString()).isEqualTo("tenant"));
        assertThat(rerun.get("data_snapshot_id").asString()).isNotEqualTo(get("/v1/reporting/runs/" + capitalRun, TENANT)
                .get("data_snapshot_id").asString());
        // an invalid override fails the run instead of silently reporting with the pack's table
        fake.overrides.set("gl_mapping.ke_cbk_gl", json.readTree("{\"rules\":[{\"line\":\"X\"}]}"));
        JsonNode bad = post("/v1/reporting/runs", runBody("KE-CBK-CA1", "2026-09-30", "{}"), MAKER, 201);
        assertThat(bad.get("status").asString()).isEqualTo("FAILED");
        assertThat(bad.get("error").asString()).contains("invalid_gl_mapping_override");
        fake.overrides.removeAll();
    }

    @Test
    @Order(8)
    void liquidityValidationFailureSurfacesAndClearsOnceTheSourceIsFixed() throws Exception {
        JsonNode run = post("/v1/reporting/runs", runBody("KE-CBK-LR1", "2026-09-30", "{}"), MAKER, 201);
        assertThat(run.get("status").asString()).isEqualTo("PARTIALLY_SUCCEEDED");
        assertThat(run.get("error_count").asInt()).isEqualTo(1);
        liquidityRun = run.get("id").asString();
        JsonNode results = get("/v1/reporting/runs/" + liquidityRun + "/validation-results", TENANT).get("data");
        assertThat(results).singleElement().satisfies(f -> {
            assertThat(f.get("rule_code").asString()).isEqualTo("LR1_LCR_RECOMPUTE");
            assertThat(f.get("severity").asString()).isEqualTo("ERROR");
            assertThat(f.get("actual_value").asString()).isEqualTo("210.00");
            assertThat(f.get("expected_value").asString()).isEqualTo("186.67");
        });
        JsonNode cells = get("/v1/reporting/runs/" + liquidityRun + "/cells?limit=200", TENANT).get("data");
        assertThat(cellValue(cells, "LR1_R220")).isEqualTo("50.42");
        assertThat(cellValue(cells, "LR1_R130")).isEqualTo("133.33");
        assertThat(outboxCount("reporting.report_run.validation_failed")).isEqualTo(1);
        // a run with ERROR findings cannot be submitted
        JsonNode csv = get("/v1/reporting/runs/" + liquidityRun + "/outputs", TENANT).get("data").get(0);
        post("/v1/reporting/submissions", "{\"report_run_id\":\"%s\",\"report_output_id\":\"%s\",\"channel\":\"PORTAL_UPLOAD\"}"
                .formatted(liquidityRun, csv.get("id").asString()), MAKER, 422);

        // treasury re-approves the corrected calculation in liquidity-service; the rerun is clean
        fake.ratios.get("lcr").getFirst().put("lcr_ratio", "1.866667");
        JsonNode rerun = post("/v1/reporting/runs/" + liquidityRun + "/rerun", "{\"reason\":\"LCR recalculated\"}", MAKER, 201);
        assertThat(rerun.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(rerun.get("error_count").asInt()).isZero();
        assertThat(rerun.get("supersedes_run_id").asString()).isEqualTo(liquidityRun);
    }

    @Test
    @Order(9)
    void largeCashRunCountsThresholdTransactionsAndDrillsDownToThem() throws Exception {
        JsonNode run = post("/v1/reporting/runs", runBody("KE-FRC-AML1", "2026-09-30", "{}"), MAKER, 201);
        assertThat(run.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(run.get("warning_count").asInt()).isEqualTo(1);
        String id = run.get("id").asString();
        assertThat(fake.calls).anyMatch(c -> c.startsWith("transaction-monitoring-service /v1/risk/monitoring/transactions?created_after=2026-09-01T00%3A00%3A00Z&created_before=2026-10-01T00%3A00%3A00Z"));
        JsonNode cells = get("/v1/reporting/runs/" + id + "/cells?limit=200", TENANT).get("data");
        assertThat(cellValue(cells, "AML1_R020")).isEqualTo("3");
        assertThat(cellValue(cells, "AML1_R030")).isEqualTo("4700000.00");
        assertThat(cellValue(cells, "AML1_R040")).isEqualTo("2");
        assertThat(cellValue(cells, "AML1_R100")).isEqualTo("1");
        assertThat(cellValue(cells, "AML1_R200")).isEqualTo("2");
        JsonNode findings = get("/v1/reporting/runs/" + id + "/validation-results", TENANT).get("data");
        assertThat(findings).extracting(f -> f.get("rule_code").asString() + "/" + f.get("severity").asString())
                .containsExactlyInAnyOrder("AML1_CTR_COVERAGE/WARNING", "AML1_STR_BACKLOG/INFO");
        JsonNode drill = get("/v1/reporting/runs/" + id + "/drilldown?cell_ref=AML1_R030", TENANT);
        List<String> ids = new ArrayList<>();
        drill.get("source_records").forEach(rec -> ids.add(rec.get("id").asString()));
        assertThat(ids).containsExactlyInAnyOrder("t1", "t2", "t5");

        // a higher threshold for this run (parameter) leaves only the KES 2m withdrawal
        JsonNode high = post("/v1/reporting/runs", runBody("KE-FRC-AML1", "2026-09-30", "{\"ctr_threshold\":1500001}"), MAKER, 201);
        JsonNode highCells = get("/v1/reporting/runs/" + high.get("id").asString() + "/cells?limit=200", TENANT).get("data");
        assertThat(cellValue(highCells, "AML1_R020")).isEqualTo("1");
        assertThat(cellValue(highCells, "AML1_R010")).isEqualTo("1500001.00");
    }

    @Test
    @Order(10)
    void filingCalendarUsesTheRegulatorsBusinessDays() throws Exception {
        JsonNode cal = get("/v1/reporting/definitions/" + definitions.get("KE-CBK-CA1") + "/calendar?from=2026-10-01&to=2026-10-31&legal_entity_id=" + le, TENANT);
        assertThat(cal.get("business_day_convention").asString()).isEqualTo("FOLLOWING");
        assertThat(cal.get("entries")).singleElement().satisfies(e -> {
            assertThat(e.get("period_end").asString()).isEqualTo("2026-09-30");
            assertThat(e.get("due_date").asString()).isEqualTo("2026-10-12"); // 10 Oct is a Saturday (and a holiday)
            assertThat(e.get("status").asString()).isIn("IN_PROGRESS", "OVERDUE");
            assertThat(e.get("report_run_id").asString()).isNotBlank();
        });
        JsonNode aml = get("/v1/reporting/definitions/" + definitions.get("KE-FRC-AML1") + "/calendar?from=2026-10-01&to=2026-11-30&legal_entity_id=" + le, TENANT);
        assertThat(aml.get("entries")).extracting(e -> e.get("period_end").asString() + ">" + e.get("due_date").asString())
                .containsExactly("2026-09-30>2026-10-09", "2026-10-31>2026-11-10");
        // the tenant adds a holiday on the definition: the business-day deadline moves
        JsonNode def = get("/v1/reporting/definitions/" + definitions.get("KE-FRC-AML1"), TENANT);
        ArrayNode holidays = ((ArrayNode) def.get("holidays")).deepCopy();
        holidays.add("2026-10-08");
        HttpResponse<String> patched = send("PATCH", "/v1/reporting/definitions/" + definitions.get("KE-FRC-AML1"),
                json.createObjectNode().set("holidays", holidays).toString(), null, "\"" + def.get("row_version").asString() + "\"", TENANT, MAKER);
        assertThat(patched.statusCode()).as(patched.body()).isEqualTo(200);
        JsonNode moved = get("/v1/reporting/definitions/" + definitions.get("KE-FRC-AML1") + "/calendar?from=2026-10-01&to=2026-10-31&legal_entity_id=" + le, TENANT);
        assertThat(moved.get("entries").get(0).get("due_date").asString()).isEqualTo("2026-10-12");
    }

    @Test
    @Order(11)
    void upgradeTouchesPackOwnedItemsOnlyAndKeepsTenantTuning() throws Exception {
        String ca = definitions.get("KE-CBK-CA1");
        String lr = definitions.get("KE-CBK-LR1");
        // simulate an older pack version: CA1's managed fields and LR1's published content differ from the pack now
        asTenant(() -> jdbc.sql("update report_definition set name = 'Renamed by an old pack', pack_checksum = 'old', "
                + "filing_offset_days = 3, pack_version = '2026.9.0' where id = ?::uuid").params(ca).update());
        asTenant(() -> jdbc.sql("update report_definition_version set pack_checksum = 'old' where report_definition_id = ?::uuid")
                .params(lr).update());
        // a report an older pack version shipped and this one no longer does
        JsonNode legacy = post("/v1/reporting/definitions", """
                {"code":"KE-CBK-OLD1","name":"Retired return","country_pack":"KE","jurisdiction":"KE","regulator":"Central Bank of Kenya",
                 "report_family":"OTHER","frequency":"MONTHLY","format":"CSV"}""", MAKER, 201);
        asTenant(() -> jdbc.sql("update report_definition set pack_code = 'KE_CBK' where id = ?::uuid")
                .params(legacy.get("id").asString()).update());

        JsonNode r = post("/v1/reporting/pack-installations/install", "{\"pack_code\":\"KE_CBK\"}", MAKER, 200);
        assertThat(r.get("updated")).extracting(u -> u.get("code").asString()).containsExactlyInAnyOrder("KE-CBK-CA1", "KE-CBK-LR1");
        assertThat(r.get("retired")).extracting(JsonNode::asString).containsExactly("KE-CBK-OLD1");
        assertThat(r.get("skipped")).extracting(s -> s.get("code").asString()).containsExactly("KE-CBK-CP1");

        JsonNode caDef = get("/v1/reporting/definitions/" + ca, TENANT);
        assertThat(caDef.get("name").asString()).isEqualTo("Capital adequacy return (illustrative CBK-style)");
        assertThat(caDef.get("filing_offset_days").asInt()).as("tenant-tuned field kept").isEqualTo(3);
        assertThat(caDef.get("pack_version").asString()).isEqualTo("2026.10.0");
        JsonNode lrVersions = get("/v1/reporting/definitions/" + lr + "/versions", TENANT).get("data");
        assertThat(lrVersions).hasSize(2);
        JsonNode v2 = find(lrVersions, "definition_version", "2");
        assertThat(v2.get("status").asString()).isEqualTo("DRAFT");
        assertThat(v2.get("change_note").asString()).startsWith("Regulatory pack KE_CBK 2026.10.0");
        assertThat(v2.get("effective_from").asString()).isGreaterThan("2026-01-01");
        assertThat(get("/v1/reporting/definitions/" + legacy.get("id").asString(), TENANT).get("status").asString()).isEqualTo("DEPRECATED");
        assertThat(get("/v1/reporting/definitions/" + definitions.get("KE-CBK-CP1"), TENANT).get("name").asString())
                .isEqualTo("Our complaints return");
        // the checker publishes the upgrade
        assertThat(post("/v1/reporting/definitions/" + lr + "/publish", "{\"definition_version\":2}", CHECKER, 200)
                .get("status").asString()).isEqualTo("ACTIVE");
        JsonNode again = post("/v1/reporting/pack-installations/install", "{\"pack_code\":\"KE_CBK\"}", MAKER, 200);
        assertThat(again.get("updated")).isEmpty();
        assertThat(again.get("retired")).isEmpty();
    }

    @Test
    @Order(12)
    void otherTenantSeesNothing() throws Exception {
        assertThat(get("/v1/reporting/pack-installations", OTHER_TENANT).get("data")).isEmpty();
        assertThat(get("/v1/reporting/definitions?country_pack=KE", OTHER_TENANT).get("data")).isEmpty();
        assertThat(get("/v1/reporting/pack-installations/catalog", OTHER_TENANT).get("count").asInt()).isEqualTo(3);
    }

    // ------------------------------------------------------------------ fakes and helpers

    /** Stateful fakes of the source services, keyed by service and path; records every call. */
    final class Fake {
        final List<ObjectNode> periods = new CopyOnWriteArrayList<>();
        final Map<String, ArrayNode> trialBalances = new LinkedHashMap<>();
        final Map<String, List<ObjectNode>> ratios = new LinkedHashMap<>();
        final List<ObjectNode> monitored = new CopyOnWriteArrayList<>();
        final List<ObjectNode> filings = new CopyOnWriteArrayList<>();
        final ObjectNode overrides = JsonMapper.builder().build().createObjectNode();
        final List<String> calls = new CopyOnWriteArrayList<>();

        JsonNode get(String service, String full) {
            calls.add(service + " " + full);
            String path = full.contains("?") ? full.substring(0, full.indexOf('?')) : full;
            Map<String, String> q = query(full);
            return switch (service + " " + path) {
                case "gl-service /v1/gl/periods" -> page(periods.stream()
                        .filter(p -> p.get("legal_entity_id").asString().equals(q.get("legal_entity_id")))
                        .filter(p -> p.get("period_type").asString().equals(q.get("period_type"))).toList());
                case "liquidity-service /v1/treasury/liquidity/lcr" -> page(ratio("lcr", q));
                case "liquidity-service /v1/treasury/liquidity/nsfr" -> page(ratio("nsfr", q));
                case "transaction-monitoring-service /v1/risk/monitoring/transactions" -> page(window(monitored, q));
                case "case-management-service /v1/risk/sar-reports" -> page(window(filings, q));
                case "config-service /v1/config/entries/resolve" -> json.createObjectNode().put("namespace", q.get("namespace"))
                        .set("values", overrides.deepCopy());
                default -> {
                    if ("gl-service".equals(service) && path.startsWith("/v1/gl/periods/") && path.endsWith("/trial-balance")) {
                        String id = path.split("/")[4];
                        ArrayNode lines = trialBalances.get(id);
                        if (lines == null) {
                            throw new ApiException(404, "not_found", "accounting period " + id + " not found");
                        }
                        ObjectNode tb = json.createObjectNode().put("accounting_period_id", id).put("balanced", true);
                        tb.set("lines", lines.deepCopy());
                        yield tb;
                    }
                    throw new ApiException(404, "not_found", "fake has no " + service + " " + path);
                }
            };
        }

        List<ObjectNode> ratio(String kind, Map<String, String> q) {
            return ratios.getOrDefault(kind, List.of()).stream()
                    .filter(r -> r.get("legal_entity_id").asString().equals(q.get("legal_entity_id")))
                    .filter(r -> r.get("as_of_date").asString().equals(q.get("as_of_date"))).toList();
        }

        List<ObjectNode> window(List<ObjectNode> rows, Map<String, String> q) {
            OffsetDateTime after = q.containsKey("created_after") ? OffsetDateTime.parse(q.get("created_after")) : null;
            OffsetDateTime before = q.containsKey("created_before") ? OffsetDateTime.parse(q.get("created_before")) : null;
            return rows.stream().filter(r -> {
                OffsetDateTime at = OffsetDateTime.parse(r.get("created_at").asString());
                return (after == null || !at.isBefore(after)) && (before == null || at.isBefore(before));
            }).toList();
        }

        JsonNode page(List<ObjectNode> rows) {
            ObjectNode p = json.createObjectNode();
            ArrayNode data = p.putArray("data");
            rows.forEach(r -> data.add(r.deepCopy()));
            p.put("has_more", false);
            return p;
        }

        Map<String, String> query(String full) {
            Map<String, String> out = new LinkedHashMap<>();
            if (full.contains("?")) {
                for (String kv : full.substring(full.indexOf('?') + 1).split("&")) {
                    String[] parts = kv.split("=", 2);
                    out.put(parts[0], parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
                }
            }
            return out;
        }
    }

    private String runBody(String code, String asOf, String params) {
        return """
                {"report_definition_id":"%s","legal_entity_id":"%s","as_of_date":"%s","trigger":"MANUAL","parameters":%s}"""
                .formatted(definitions.get(code), le, asOf, params);
    }

    private static JsonNode find(JsonNode arr, String field, String value) {
        for (JsonNode n : arr) {
            if (value.equals(n.path(field).asString())) {
                return n;
            }
        }
        throw new AssertionError("no element with " + field + "=" + value + " in " + arr);
    }

    private static String cellValue(JsonNode cells, String ref) {
        for (JsonNode c : cells) {
            if (ref.equals(c.get("cell_ref").asString())) {
                return c.get("value").isNull() ? null : c.get("value").asString();
            }
        }
        throw new AssertionError("no cell " + ref);
    }

    private <T> T asTenant(Callable<T> body) throws Exception {
        return TenantContext.callAs(UUID.fromString(TENANT), "test", body);
    }

    private long outboxCount(String type) throws Exception {
        return asTenant(() -> jdbc.sql("select count(*) from outbox where event_type = ? and tenant_id = ?::uuid")
                .params(type, TENANT).query(Long.class).single());
    }

    private static String code(JsonNode problem) {
        return problem.path("code").asString();
    }

    private JsonNode post(String path, String body, String user, int expected) throws Exception {
        HttpResponse<String> r = send("POST", path, body, UUID.randomUUID().toString(), null, TENANT, user);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path, String tenant) throws Exception {
        HttpResponse<String> r = send("GET", path, null, null, null, tenant, MAKER);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String ifMatch,
                                      String tenant, String user) throws Exception {
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
