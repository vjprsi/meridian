package io.meridian.businessday;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.meridian.businessday.config.AutoEodScheduling;
import io.meridian.businessday.application.EodTriggerService;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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

/**
 * G8: the standard EOD job pack and the cross-tenant EOD trigger against PostgreSQL 18 (RLS on). tenant-service
 * (tenant list, legal entity time zones) and every downstream job service are stubbed through {@link ServiceClient}.
 * Tenants: A and B are due and get EOD started; C already ran EOD for its date (skipped); D has a corrupt job graph
 * (fails, listed first, must not block the others); E's cut-off has not been reached.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.business-day.eod.retry-backoff=PT0.02S",
                "meridian.business-day.auto-eod.enabled=true",
                "meridian.business-day.auto-eod.interval=PT24H",
                "meridian.business-day.auto-eod.initial-delay=PT24H",
                "meridian.business-day.auto-eod.default-cut-off=00:00",
                "meridian.business-day.auto-eod.default-time-zone=UTC"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AutoEodFlowIT {
    static final String OPERATOR = "0199a000-0000-7000-8000-0000000000a0";
    static final String A = "0199a000-0000-7000-8000-0000000000c1";
    static final String B = "0199a000-0000-7000-8000-0000000000c2";
    static final String C = "0199a000-0000-7000-8000-0000000000c3";
    static final String D = "0199a000-0000-7000-8000-0000000000c4";
    static final String E = "0199a000-0000-7000-8000-0000000000c5";
    static final String EA = "0199a000-0000-7000-8000-00000000ea01";
    static final String EB = "0199a000-0000-7000-8000-00000000eb01";
    static final String EC = "0199a000-0000-7000-8000-00000000ec01";
    static final String ED = "0199a000-0000-7000-8000-00000000ed01";
    static final String EE = "0199a000-0000-7000-8000-00000000ee01";
    static final String LEDGER = "0199a000-0000-7000-8000-0000000001ed";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MeterRegistry meters;

    @Autowired
    AutoEodScheduling scheduler;

    @Autowired
    EodTriggerService trigger;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    /** Every job dispatch: tenant in context, method, path, body. */
    record Dispatch(String tenant, String method, String path, JsonNode body) {}
    final List<Dispatch> dispatches = Collections.synchronizedList(new ArrayList<>());
    final AtomicBoolean tenantServiceDown = new AtomicBoolean();
    final Map<String, String> dayOf = new HashMap<>();
    String firstSweepKey;
    String firstSweepId;

    @BeforeEach
    void stubs() {
        when(serviceClient.send(anyString(), anyString(), anyString(), any(), any())).thenAnswer(inv -> {
            dispatches.add(new Dispatch(TenantContext.current().map(UUID::toString).orElse(null), inv.getArgument(1),
                    inv.getArgument(2), (JsonNode) inv.getArgument(3)));
            return json.createObjectNode().put("records_processed", 1).put("records_failed", 0);
        });
        when(serviceClient.get(anyString(), anyString())).thenAnswer(inv -> {
            String path = inv.getArgument(1);
            if (path.startsWith("/v1/platform/tenants?")) {
                if (tenantServiceDown.get()) {
                    throw new ApiException(502, "upstream_error", "tenant-service is not reachable");
                }
                assertThat(path).contains("status=ACTIVE");
                return json.readTree("""
                        {"data":[
                          {"id":"%s","code":"broken","status":"ACTIVE","timezone":"UTC"},
                          {"id":"%s","code":"alpha","status":"ACTIVE","timezone":"Europe/London"},
                          {"id":"%s","code":"bravo","status":"ACTIVE","timezone":"Europe/London"},
                          {"id":"%s","code":"charlie","status":"ACTIVE","timezone":"UTC"},
                          {"id":"%s","code":"echo","status":"ACTIVE","timezone":"UTC"}
                        ],"has_more":false}""".formatted(D, A, B, C, E));
            }
            if (path.equals("/v1/legal-entities/" + EA)) {
                return json.readTree("{\"id\":\"" + EA + "\",\"timezone\":\"Africa/Nairobi\"}");
            }
            throw new ApiException(404, "not_found", path);
        });
    }

    // ------------------------------------------------------------------ standard job pack

    @Test
    @Order(1)
    void standardPackInstallIsIdempotent() throws Exception {
        JsonNode pack = get("/v1/operations/eod-jobs/standard-pack", A);
        String version = pack.get("version").asString();
        int jobs = pack.get("jobs").size();
        assertThat(jobs).isGreaterThanOrEqualTo(40);

        JsonNode dry = post("/v1/operations/eod-jobs/install-standard-pack", "{\"dry_run\":true}", A, 200);
        assertThat(dry.get("created")).hasSize(jobs);
        assertThat(dry.get("dry_run").asBoolean()).isTrue();
        assertThat(get("/v1/operations/eod-jobs?limit=200", A).get("data")).isEmpty();

        JsonNode wrong = post("/v1/operations/eod-jobs/install-standard-pack", "{\"version\":\"1999.1.0\"}", A, 422);
        assertThat(wrong.get("code").asString()).isEqualTo("unknown_pack_version");

        String key = UUID.randomUUID().toString();
        HttpResponse<String> first = send("POST", "/v1/operations/eod-jobs/install-standard-pack",
                "{\"version\":\"" + version + "\"}", key, A);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        JsonNode installed = json.readTree(first.body());
        assertThat(installed.get("created")).hasSize(jobs);
        assertThat(installed.get("needs_parameters")).isEmpty();
        assertThat(get("/v1/operations/eod-jobs?limit=200", A).get("data")).hasSize(jobs);
        HttpResponse<String> replay = send("POST", "/v1/operations/eod-jobs/install-standard-pack",
                "{\"version\":\"" + version + "\"}", key, A);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");

        JsonNode again = post("/v1/operations/eod-jobs/install-standard-pack", "{}", A, 200);
        assertThat(again.get("created")).isEmpty();
        assertThat(again.get("updated")).isEmpty();
        assertThat(again.get("unchanged")).hasSize(jobs);
        assertThat(get("/v1/operations/eod-jobs?limit=200", A).get("data")).hasSize(jobs);

        // tenant tuning survives an upgrade; contract fields drifted from the pack are restored
        JsonNode dormancy = job(A, "DEP_DORMANCY");
        HttpResponse<String> tune = send("PATCH", "/v1/operations/eod-jobs/" + dormancy.get("id").asString(),
                "{\"max_retries\":5}", null, A, dormancy.get("row_version").asString());
        assertThat(tune.statusCode()).as(tune.body()).isEqualTo(200);
        TenantContext.callAs(UUID.fromString(A), "test", () -> jdbc.sql("""
                update eod_job_definition set http_path = '/v1/old/dormancy', pack_version = '2026.1.0'
                where code = 'DEP_DORMANCY'""").update());
        JsonNode upgrade = post("/v1/operations/eod-jobs/install-standard-pack", "{}", A, 200);
        assertThat(upgrade.get("updated").toString()).isEqualTo("[\"DEP_DORMANCY\"]");
        JsonNode upgraded = job(A, "DEP_DORMANCY");
        assertThat(upgraded.get("http_path").asString()).startsWith("/v1/deposit-accounts/eod/dormancy");
        assertThat(upgraded.get("max_retries").asInt()).isEqualTo(5);
        assertThat(upgraded.get("pack_version").asString()).isEqualTo(version);

        // LEDGER_EOD runs every book of the tenant out of the box (no parameters)
        JsonNode ledger = job(A, "LEDGER_EOD");
        assertThat(ledger.get("is_active").asBoolean()).isTrue();
        assertThat(ledger.get("http_path").asString()).isEqualTo("/v1/ledgers/end-of-day");
        // a definition from the per-book pack that waited for parameters.ledger_id is upgraded and activated
        TenantContext.callAs(UUID.fromString(A), "test", () -> jdbc.sql("""
                update eod_job_definition set http_path = '/v1/ledgers/{parameters.ledger_id}/eod', is_active = false,
                       parameters = '{"ledger_id":null}'::jsonb, pack_version = '2026.10.1'
                where code = 'LEDGER_EOD'""").update());
        JsonNode legacy = post("/v1/operations/eod-jobs/install-standard-pack", "{}", A, 200);
        assertThat(legacy.get("updated").toString()).contains("LEDGER_EOD");
        assertThat(legacy.get("activated").toString()).isEqualTo("[\"LEDGER_EOD\"]");
        ledger = job(A, "LEDGER_EOD");
        assertThat(ledger.get("is_active").asBoolean()).isTrue();
        assertThat(ledger.get("http_path").asString()).isEqualTo("/v1/ledgers/end-of-day");
        // a tenant may still pin one book (backward compatible with the per-book job)
        HttpResponse<String> activated = send("PATCH", "/v1/operations/eod-jobs/" + ledger.get("id").asString(),
                "{\"parameters\":{\"ledger_id\":\"" + LEDGER + "\"}}", null, A,
                ledger.get("row_version").asString());
        assertThat(activated.statusCode()).as(activated.body()).isEqualTo(200);
        assertThat(post("/v1/operations/eod-jobs/validate", null, A, 200).get("valid").asBoolean()).isTrue();

        // a tenant-defined job with a pack code is never overwritten
        post("/v1/operations/eod-jobs", """
                {"code":"DEP_DORMANCY","name":"Own dormancy","job_type":"DORMANCY","cycle_types":["EOD"],
                 "owning_service":"deposit-service","http_path":"/v1/deposit-accounts/eod/dormancy?business_date={business_date}"}""",
                B, 201);
        JsonNode b = post("/v1/operations/eod-jobs/install-standard-pack", "{}", B, 200);
        assertThat(b.get("created")).hasSize(jobs - 1);
        assertThat(b.get("skipped").get(0).get("code").asString()).isEqualTo("DEP_DORMANCY");
        assertThat(b.get("skipped").get(0).get("reason").asString()).isEqualTo("tenant_defined");
        assertThat(job(B, "DEP_DORMANCY").get("name").asString()).isEqualTo("Own dormancy");
    }

    // ------------------------------------------------------------------ trigger policies

    @Test
    @Order(2)
    void triggerPoliciesAreValidated() throws Exception {
        assertThat(post("/v1/operations/eod-trigger-policies", "{\"legal_entity_id\":\"" + EE + "\",\"cut_off_time\":\"24:00\"}",
                E, 422).get("code").asString()).isEqualTo("invalid_cut_off_time");
        assertThat(post("/v1/operations/eod-trigger-policies", "{\"legal_entity_id\":\"" + EE
                + "\",\"cut_off_time\":\"18:00\",\"time_zone\":\"Mars/Base\"}", E, 422).get("code").asString())
                .isEqualTo("invalid_time_zone");
        JsonNode p = post("/v1/operations/eod-trigger-policies", "{\"legal_entity_id\":\"" + EE
                + "\",\"cut_off_time\":\"23:59\",\"time_zone\":\"Pacific/Kiritimati\"}", E, 201);
        assertThat(p.get("is_enabled").asBoolean()).isTrue();
        assertThat(post("/v1/operations/eod-trigger-policies", "{\"legal_entity_id\":\"" + EE + "\",\"cut_off_time\":\"20:00\"}",
                E, 409).get("code").asString()).isEqualTo("policy_exists");
        post("/v1/operations/eod-trigger-policies", "{\"cut_off_time\":\"20:00\"}", E, 201);
        assertThat(post("/v1/operations/eod-trigger-policies", "{\"cut_off_time\":\"21:00\"}", E, 409).get("code").asString())
                .isEqualTo("policy_exists");
    }

    // ------------------------------------------------------------------ cross-tenant trigger

    @Test
    @Order(3)
    void prepareTenants() throws Exception {
        for (String[] t : new String[][] {{A, EA, "2026-01-06"}, {B, EB, "2026-01-06"}, {C, EC, "2026-01-06"},
                {D, ED, "2026-01-06"}, {E, EE, "2099-01-05"}}) {
            JsonNode day = post("/v1/operations/business-days",
                    "{\"legal_entity_id\":\"" + t[1] + "\",\"business_date\":\"" + t[2] + "\"}", t[0], 201);
            dayOf.put(t[0], day.get("id").asString());
        }
        // C: EOD already ran today (operator started it)
        post("/v1/operations/eod-jobs", """
                {"code":"C_ACCRUAL","name":"Accrual","job_type":"INTEREST_ACCRUAL","cycle_types":["EOD"],
                 "owning_service":"deposit-service","http_path":"/v1/deposit-accounts/eod/accrue?business_date={business_date}"}""",
                C, 201);
        JsonNode run = post("/v1/operations/business-days/" + dayOf.get(C) + "/start-eod", "{}", C, 200);
        assertThat(awaitRun(C, run.get("id").asString()).get("status").asString()).isEqualTo("SUCCEEDED");
        // D: a job graph corrupted behind the API's back (a cycle): starting EOD fails for this tenant only
        post("/v1/operations/eod-jobs/install-standard-pack", "{}", D, 200);
        TenantContext.callAs(UUID.fromString(D), "test", () -> jdbc.sql("""
                insert into eod_job_definition (tenant_id, code, name, job_type, cycle_types, owning_service, http_path,
                                                depends_on)
                values (?::uuid, 'LOOP_X', 'x', 'CUSTOM', '["EOD"]', 'deposit-service', '/x', '["LOOP_Y"]'),
                       (?::uuid, 'LOOP_Y', 'y', 'CUSTOM', '["EOD"]', 'deposit-service', '/y', '["LOOP_X"]')""")
                .params(D, D).update());
        post("/v1/operations/eod-jobs/install-standard-pack", "{}", E, 200);

        JsonNode eval = get("/v1/operations/eod-trigger-policies/evaluation", E);
        assertThat(eval.get("entities")).hasSize(1);
        assertThat(eval.get("entities").get(0).get("decision").asString()).isEqualTo("NOT_DUE");
        assertThat(eval.get("entities").get(0).get("policy").asString()).isEqualTo("LEGAL_ENTITY");
        assertThat(eval.get("entities").get(0).get("time_zone").asString()).isEqualTo("Pacific/Kiritimati");
    }

    @Test
    @Order(4)
    void operatorSweepStartsDueTenantsSkipsDoneOnesAndIsolatesFailures() throws Exception {
        dispatches.clear();
        firstSweepKey = UUID.randomUUID().toString();
        HttpResponse<String> r = send("POST", "/v1/operations/eod-sweeps/run", "{}", firstSweepKey, OPERATOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode sweep = json.readTree(r.body());
        firstSweepId = sweep.get("id").asString();
        assertThat(sweep.get("trigger_source").asString()).isEqualTo("OPERATOR");
        assertThat(sweep.get("status").asString()).isEqualTo("PARTIALLY_SUCCEEDED");
        assertThat(sweep.get("tenants_total").asInt()).isEqualTo(5);
        assertThat(sweep.get("tenants_failed").asInt()).isEqualTo(1);
        assertThat(sweep.get("runs_started").asInt()).isEqualTo(2);
        assertThat(sweep.get("entities_skipped").asInt()).isEqualTo(2);
        assertThat(sweep.get("finished_at").isNull()).isFalse();

        Map<String, JsonNode> byTenant = results(sweep);
        assertThat(byTenant.get(D).get("decision").asString()).isEqualTo("FAILED"); // listed first, did not block
        assertThat(byTenant.get(D).get("error_code").asString()).isEqualTo("dependency_cycle");
        assertThat(byTenant.get(A).get("decision").asString()).isEqualTo("START");
        assertThat(byTenant.get(A).get("time_zone").asString()).isEqualTo("Africa/Nairobi"); // legal entity zone
        assertThat(byTenant.get(A).get("policy").asString()).isEqualTo("PLATFORM_DEFAULT");
        assertThat(byTenant.get(B).get("decision").asString()).isEqualTo("START");
        assertThat(byTenant.get(B).get("time_zone").asString()).isEqualTo("Europe/London"); // tenant zone fallback
        assertThat(byTenant.get(C).get("decision").asString()).isEqualTo("ALREADY_RAN");
        assertThat(byTenant.get(E).get("decision").asString()).isEqualTo("NOT_DUE");

        // EOD really started under each tenant, by the scheduler identity, and completes with the pack's jobs
        for (String t : List.of(A, B)) {
            String runId = byTenant.get(t).get("eod_run_id").asString();
            JsonNode run = awaitRun(t, runId);
            assertThat(run.get("status").asString()).as(run.toString()).isEqualTo("SUCCEEDED");
            assertThat(run.get("triggered_by").asString()).isEqualTo("SCHEDULER");
            assertThat(run.get("cycle_type").asString()).isEqualTo("EOD");
            assertThat(get("/v1/operations/eod-runs?business_day_id=" + dayOf.get(t), t).get("data")).hasSize(1);
        }
        assertThat(get("/v1/operations/eod-runs?business_day_id=" + dayOf.get(D), D).get("data")).isEmpty();
        assertThat(get("/v1/operations/business-days/" + dayOf.get(D), D).get("state").asString()).isEqualTo("OPEN");

        List<Dispatch> a = dispatches.stream().filter(d -> A.equals(d.tenant())).toList();
        List<String> aPaths = a.stream().map(Dispatch::path).toList();
        assertThat(index(aPaths, "/v1/loans/eod/auto-debit")).isLessThan(index(aPaths, "/v1/deposit-accounts/eod/accrue"));
        assertThat(index(aPaths, "/v1/payments/execute-due")).isLessThan(index(aPaths, "/v1/deposit-accounts/eod/standing-orders"));
        assertThat(index(aPaths, "/v1/loans/eod/delinquency")).isLessThan(index(aPaths, "/v1/collections/cases/eod/strategy-run"));
        assertThat(index(aPaths, "/v1/ledgers/end-of-day")).isGreaterThan(index(aPaths, "/v1/deposit-accounts/eod/accrue"));
        assertThat(index(aPaths, "/v1/statements/cycle")).isGreaterThan(index(aPaths, "/v1/ledgers/end-of-day"));
        assertThat(aPaths).noneMatch(p -> p.startsWith("/v1/gl/ecl-provisions")); // EOM only
        Dispatch ledgerEod = a.stream().filter(d -> d.path().startsWith("/v1/ledgers/")).findFirst().orElseThrow();
        assertThat(ledgerEod.body().get("business_date").asString()).isEqualTo("2026-01-06");
        assertThat(ledgerEod.body().get("ledger_id").asString()).isEqualTo(LEDGER);
        assertThat(ledgerEod.body().get("legal_entity_id").asString()).isEqualTo(EA);
        assertThat(aPaths).contains("/v1/deposit-accounts/eod/accrue?business_date=2026-01-06");
        // B left LEDGER_EOD as shipped: every book of its legal entity is closed, after the accruals
        List<Dispatch> b = dispatches.stream().filter(d -> B.equals(d.tenant())).toList();
        List<String> bPaths = b.stream().map(Dispatch::path).toList();
        Dispatch bLedger = b.stream().filter(d -> d.path().equals("/v1/ledgers/end-of-day")).findFirst().orElseThrow();
        assertThat(bLedger.body().get("ledger_id").isNull()).isTrue();
        assertThat(bLedger.body().get("legal_entity_id").asString()).isEqualTo(EB);
        assertThat(index(bPaths, "/v1/deposit-accounts/eod/accrue")).isLessThan(index(bPaths, "/v1/ledgers/end-of-day"));
        assertThat(index(bPaths, "/v1/deposit-accounts/eod/accrue")).isLessThan(index(bPaths, "/v1/statements/cycle"));
        assertThat(dispatches).noneMatch(d -> d.tenant() == null || D.equals(d.tenant()) || E.equals(d.tenant()));

        // replaying the operator request returns the same sweep
        HttpResponse<String> replay = send("POST", "/v1/operations/eod-sweeps/run", "{}", firstSweepKey, OPERATOR);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(replay.body()).get("id").asString()).isEqualTo(firstSweepId);

        // events and metrics
        List<String> events = jdbc.sql("select event_type || '@' || tenant_id from outbox where event_type like 'operations.eod_sweep.%'")
                .query(String.class).list();
        assertThat(events).contains("operations.eod_sweep.completed@" + TenantContext.NO_TENANT,
                "operations.eod_sweep.tenant_failed@" + D);
        assertThat(meters.counter("meridian.eod.trigger.decisions", "decision", "START").count()).isGreaterThanOrEqualTo(2);
        assertThat(meters.counter("meridian.eod.trigger.decisions", "decision", "FAILED").count()).isGreaterThanOrEqualTo(1);
        assertThat(get("/v1/operations/eod-sweeps/" + firstSweepId, OPERATOR).get("runs_started").asInt()).isEqualTo(2);
    }

    @Test
    @Order(5)
    void secondSweepIsIdempotentPerTenantAndBusinessDate() throws Exception {
        dispatches.clear();
        JsonNode sweep = post("/v1/operations/eod-sweeps/run", "{\"tenant_ids\":[\"" + A + "\",\"" + B + "\"]}", OPERATOR, 200);
        assertThat(sweep.get("tenants_total").asInt()).isEqualTo(2);
        assertThat(sweep.get("runs_started").asInt()).isZero();
        assertThat(sweep.get("status").asString()).isEqualTo("SUCCEEDED");
        Map<String, JsonNode> byTenant = results(sweep);
        assertThat(byTenant.get(A).get("decision").asString()).isEqualTo("ALREADY_RAN");
        assertThat(byTenant.get(B).get("decision").asString()).isEqualTo("ALREADY_RAN");
        assertThat(get("/v1/operations/eod-runs?business_day_id=" + dayOf.get(A), A).get("data")).hasSize(1);
        assertThat(dispatches).isEmpty();
    }

    @Test
    @Order(6)
    void schedulerTickClaimsItsSlotOnce() throws Exception {
        long before = jdbc.sql("select count(*) from eod_sweep where trigger_source = 'SCHEDULER'").query(Long.class).single();
        scheduler.tick();
        assertThat(trigger.tick()).isEmpty(); // same slot: another instance (or this one) already swept it
        long after = jdbc.sql("select count(*) from eod_sweep where trigger_source = 'SCHEDULER'").query(Long.class).single();
        assertThat(after).isEqualTo(before + 1);
        JsonNode s = json.readTree(jdbc.sql("""
                select row_to_json(s)::text from eod_sweep s where trigger_source = 'SCHEDULER'
                order by created_at desc limit 1""").query(String.class).single());
        assertThat(s.get("requested_by").asString()).isEqualTo("SCHEDULER");
        assertThat(s.get("tenants_total").asInt()).isEqualTo(5);
        assertThat(s.get("runs_started").asInt()).isZero(); // A and B ran, C ran, D broken, E not due
        assertThat(s.get("tenants_failed").asInt()).isEqualTo(1);
    }

    @Test
    @Order(7)
    void autoRollAfterSuccessThenNextDateIsStarted() throws Exception {
        post("/v1/operations/eod-trigger-policies", "{\"cut_off_time\":\"00:00\",\"auto_roll\":true}", A, 201);
        JsonNode roll = post("/v1/operations/eod-sweeps/run", "{\"tenant_ids\":[\"" + A + "\"]}", OPERATOR, 200);
        JsonNode r = results(roll).get(A);
        assertThat(r.get("decision").asString()).isEqualTo("ROLL");
        assertThat(r.get("policy").asString()).isEqualTo("TENANT_DEFAULT");
        assertThat(r.get("to_business_date").asString()).isEqualTo("2026-01-07");
        assertThat(roll.get("dates_rolled").asInt()).isEqualTo(1);
        JsonNode current = get("/v1/operations/business-days/current?legal_entity_id=" + EA, A);
        assertThat(current.get("business_date").asString()).isEqualTo("2026-01-07");

        // the next sweep catches up: the new date is already past its cut-off
        JsonNode next = post("/v1/operations/eod-sweeps/run", "{\"tenant_ids\":[\"" + A + "\"]}", OPERATOR, 200);
        assertThat(results(next).get(A).get("decision").asString()).isEqualTo("START");
        assertThat(results(next).get(A).get("business_date").asString()).isEqualTo("2026-01-07");
        awaitRun(A, results(next).get(A).get("eod_run_id").asString());
    }

    @Test
    @Order(8)
    void tenantServiceOutageFailsTheSweepCleanly() throws Exception {
        tenantServiceDown.set(true);
        JsonNode sweep = post("/v1/operations/eod-sweeps/run", "{}", OPERATOR, 200);
        assertThat(sweep.get("status").asString()).isEqualTo("FAILED");
        assertThat(sweep.get("error").asString()).contains("tenant-service");
        assertThat(sweep.get("tenants_total").asInt()).isZero();
        tenantServiceDown.set(false);
        JsonNode list = get("/v1/operations/eod-sweeps?trigger_source=OPERATOR&limit=200", OPERATOR);
        assertThat(list.get("data").size()).isGreaterThanOrEqualTo(5);
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, JsonNode> results(JsonNode sweep) {
        Map<String, JsonNode> out = new HashMap<>();
        sweep.get("results").forEach(r -> out.put(r.get("tenant_id").asString(), r));
        return out;
    }

    private static int index(List<String> paths, String prefix) {
        for (int i = 0; i < paths.size(); i++) {
            if (paths.get(i).startsWith(prefix)) {
                return i;
            }
        }
        throw new AssertionError("no dispatch to " + prefix + " in " + paths);
    }

    private JsonNode job(String tenant, String code) throws Exception {
        for (JsonNode j : get("/v1/operations/eod-jobs?limit=200", tenant).get("data")) {
            if (j.get("code").asString().equals(code)) {
                return j;
            }
        }
        throw new AssertionError("job " + code + " not found");
    }

    private JsonNode awaitRun(String tenant, String id) throws Exception {
        long deadline = System.currentTimeMillis() + 60_000;
        JsonNode run;
        do {
            run = get("/v1/operations/eod-runs/" + id, tenant);
            String s = run.get("status").asString();
            if (!s.equals("RUNNING") && !s.equals("QUEUED")) {
                Thread.sleep(100);
                return get("/v1/operations/eod-runs/" + id, tenant);
            }
            Thread.sleep(50);
        } while (System.currentTimeMillis() < deadline);
        throw new AssertionError("run " + id + " did not finish: " + run);
    }

    private JsonNode post(String path, String body, String tenant, int expected) throws Exception {
        HttpResponse<String> r = send("POST", path, body, UUID.randomUUID().toString(), tenant);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
    }

    private JsonNode get(String path, String tenant) throws Exception {
        HttpResponse<String> r = send("GET", path, null, null, tenant);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String tenant)
            throws Exception {
        return send(method, path, body, idemKey, tenant, null);
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String tenant,
                                      String ifMatch) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", tenant).header("X-User", "ops-user").header("Content-Type", "application/json");
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
