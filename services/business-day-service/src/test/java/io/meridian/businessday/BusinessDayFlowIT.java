package io.meridian.businessday;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.meridian.businessday.infrastructure.LockStore;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
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
 * Business day lifecycle and EOD orchestration over HTTP against PostgreSQL 18 (RLS on). Downstream job
 * services and reference data are stubbed through {@link ServiceClient}.
 * Default calendar: Sat/Sun weekend plus a configured holiday on Monday 2 Feb 2026.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.business-day.holidays=2026-02-02", "meridian.business-day.eod.retry-backoff=PT0.02S"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BusinessDayFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000b1";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-0000000000b2";
    static final String ENTITY = "0199a000-0000-7000-8000-00000000e001";
    static final String CAL_ENTITY = "0199a000-0000-7000-8000-00000000e002";
    static final String CALENDAR = "0199a000-0000-7000-8000-00000000ca01";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    LockStore lockStore;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
    final Map<String, IntFunction<JsonNode>> behaviour = new ConcurrentHashMap<>();
    String dayId;
    String runId;

    /** Stubs are re-registered per test: @MockitoBean mocks are reset after each test method. */
    @BeforeEach
    void stubs() {
        when(serviceClient.post(anyString(), anyString(), any(), any())).thenAnswer(inv -> {
            String path = inv.getArgument(1);
            calls.add(path);
            int n = counts.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
            IntFunction<JsonNode> b = behaviour.get(path);
            return b == null ? ok(5) : b.apply(n);
        });
        when(serviceClient.get(anyString(), anyString())).thenAnswer(inv -> {
            String path = inv.getArgument(1);
            if (path.startsWith("/v1/reference/holiday-calendars/")) {
                return json.readTree("{\"id\":\"" + CALENDAR + "\",\"weekend_days\":{\"days\":[\"FRIDAY\",\"SATURDAY\"]}}");
            }
            if (path.startsWith("/v1/reference/holidays")) {
                return json.readTree("""
                        {"data":[{"holiday_date":"2026-03-08","is_half_day":false}],"has_more":false}""");
            }
            throw new ApiException(404, "not_found", path);
        });
    }

    JsonNode ok(long records) {
        return json.createObjectNode().put("records_processed", records).put("records_failed", 0);
    }

    // ------------------------------------------------------------------ job graph

    @Test
    @Order(1)
    void jobGraphIsValidatedOnEveryChange() throws Exception {
        jobDef("ACCRUAL", "INTEREST_ACCRUAL", "/jobs/accrual", "[]", 1, true, "RETRY_THEN_STOP", 10);
        jobDef("CAPITALISE", "INTEREST_CAPITALISATION", "/jobs/capitalise", "[\"ACCRUAL\"]", 2, true, "RETRY_THEN_STOP", 20);
        jobDef("STATEMENTS", "STATEMENTS", "/jobs/statements", "[\"ACCRUAL\"]", 1, false, "RETRY_THEN_CONTINUE", 30);
        String gl = jobDef("GL_CLOSE", "GL_CLOSE", "/jobs/gl-close", "[\"CAPITALISE\"]", 1, true, "RETRY_THEN_STOP", 40);

        JsonNode missing = postJson("/v1/operations/eod-jobs", jobBody("BROKEN", "CUSTOM", "/jobs/x", "[\"NOPE\"]", 1, true,
                "CONTINUE", 50), 422);
        assertThat(missing.get("code").asString()).isEqualTo("missing_dependency");

        JsonNode badService = postJson("/v1/operations/eod-jobs", jobBody("X", "CUSTOM", "/jobs/x", "[]", 1, true,
                "CONTINUE", 50).replace("deposit-service", "no-such-service"), 422);
        assertThat(badService.get("code").asString()).isEqualTo("unknown_service");

        // ACCRUAL -> CAPITALISE -> GL_CLOSE; making ACCRUAL depend on GL_CLOSE closes a cycle
        JsonNode accrual = get("/v1/operations/eod-jobs?limit=200", TENANT).get("data");
        String accrualId = null;
        for (JsonNode j : accrual) {
            if (j.get("code").asString().equals("ACCRUAL")) {
                accrualId = j.get("id").asString();
            }
        }
        HttpResponse<String> cyc = send("PATCH", "/v1/operations/eod-jobs/" + accrualId,
                "{\"depends_on\":{\"codes\":[\"GL_CLOSE\"]}}", null, TENANT, "dev-user", "1");
        assertThat(cyc.statusCode()).as(cyc.body()).isEqualTo(422);
        assertThat(json.readTree(cyc.body()).get("code").asString()).isEqualTo("dependency_cycle");

        HttpResponse<String> del = send("DELETE", "/v1/operations/eod-jobs/" + accrualId, null, null, TENANT, "dev-user", null);
        assertThat(del.statusCode()).isEqualTo(409);
        assertThat(json.readTree(del.body()).get("code").asString()).isEqualTo("job_has_dependents");

        JsonNode graph = get("/v1/operations/eod-jobs/graph?cycle_type=EOD", TENANT);
        assertThat(graph.get("valid").asBoolean()).isTrue();
        assertThat(graph.get("levels").toString())
                .isEqualTo("[[\"ACCRUAL\"],[\"CAPITALISE\",\"STATEMENTS\"],[\"GL_CLOSE\"]]");
        assertThat(graph.get("edges")).hasSize(3);

        JsonNode report = postJson("/v1/operations/eod-jobs/validate", null, 200);
        assertThat(report.get("valid").asBoolean()).isTrue();
        assertThat(report.get("cycles").get("EOM").get("job_count").asInt()).isEqualTo(4);
        assertThat(gl).isNotNull();
    }

    // ------------------------------------------------------------------ business day + EOD

    @Test
    @Order(2)
    void openCurrentAndCalendar() throws Exception {
        JsonNode day = postJson("/v1/operations/business-days",
                "{\"legal_entity_id\":\"" + ENTITY + "\",\"business_date\":\"2026-01-30\"}", 201);
        dayId = day.get("id").asString();
        assertThat(day.get("state").asString()).isEqualTo("OPEN");
        assertThat(day.get("is_current").asBoolean()).isTrue();
        assertThat(day.get("is_month_end").asBoolean()).isTrue();
        JsonNode dup = postJson("/v1/operations/business-days",
                "{\"legal_entity_id\":\"" + ENTITY + "\",\"business_date\":\"2026-02-03\"}", 409);
        assertThat(dup.get("code").asString()).isEqualTo("business_day_already_open");
        assertThat(get("/v1/operations/business-days/current?legal_entity_id=" + ENTITY, TENANT).get("id").asString())
                .isEqualTo(dayId);

        // calendar from reference-data: Fri/Sat weekend
        JsonNode friday = postJson("/v1/operations/business-days", "{\"legal_entity_id\":\"" + CAL_ENTITY
                + "\",\"business_date\":\"2026-03-06\",\"holiday_calendar_id\":\"" + CALENDAR + "\"}", 422);
        assertThat(friday.get("code").asString()).isEqualTo("not_a_business_day");
        JsonNode thursday = postJson("/v1/operations/business-days", "{\"legal_entity_id\":\"" + CAL_ENTITY
                + "\",\"business_date\":\"2026-03-05\",\"holiday_calendar_id\":\"" + CALENDAR + "\"}", 201);
        assertThat(thursday.get("is_month_end").asBoolean()).isFalse();
    }

    @Test
    @Order(3)
    void invalidTransitionsAreRejected() throws Exception {
        JsonNode roll = postJson("/v1/operations/business-days/" + dayId + "/roll", "{}", 409);
        assertThat(roll.get("code").asString()).isEqualTo("invalid_transition");
        JsonNode cut = postJson("/v1/operations/business-days/" + dayId + "/cut-off", "{\"reason\":\"18:00 cut-off\"}", 200);
        assertThat(cut.get("state").asString()).isEqualTo("CUT_OFF");
        assertThat(postJson("/v1/operations/business-days/" + dayId + "/cut-off", "{}", 409).get("code").asString())
                .isEqualTo("invalid_transition");
        JsonNode reopen = postJson("/v1/operations/business-days/" + dayId + "/reopen", "{\"reason\":\"x\"}", 409);
        assertThat(reopen.get("code").asString()).isEqualTo("invalid_transition");
    }

    @Test
    @Order(4)
    void eodRunsInDependencyOrderWithRetriesAndPartialFailure() throws Exception {
        // first CAPITALISE call fails with a transient 502 -> retried; STATEMENTS (non-critical) always fails
        behaviour.put("/jobs/capitalise", n -> {
            if (n == 1) {
                throw new ApiException(502, "upstream_error", "deposit-service timeout");
            }
            return ok(100);
        });
        behaviour.put("/jobs/statements", n -> {
            throw new ApiException(503, "upstream_error", "statement-service down");
        });
        String key = UUID.randomUUID().toString();
        HttpResponse<String> r = send("POST", "/v1/operations/business-days/" + dayId + "/start-eod", "{}", key, TENANT,
                "operator", null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode run = json.readTree(r.body());
        runId = run.get("id").asString();
        assertThat(run.get("cycle_type").asString()).isEqualTo("EOM"); // 30 Jan is the month end
        assertThat(run.get("jobs_total").asInt()).isEqualTo(4);

        HttpResponse<String> replay = send("POST", "/v1/operations/business-days/" + dayId + "/start-eod", "{}", key,
                TENANT, "operator", null);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(replay.body()).get("id").asString()).isEqualTo(runId);

        JsonNode done = awaitRun(runId);
        assertThat(done.get("status").asString()).isEqualTo("PARTIALLY_SUCCEEDED");
        assertThat(done.get("jobs_succeeded").asInt()).isEqualTo(3);
        assertThat(done.get("jobs_failed").asInt()).isEqualTo(1);
        assertThat(get("/v1/operations/business-days/" + dayId, TENANT).get("state").asString()).isEqualTo("EOD_RUNNING");

        // ordering: accrual first, gl-close after both capitalise partitions
        assertThat(calls.getFirst()).isEqualTo("/jobs/accrual");
        assertThat(calls.lastIndexOf("/jobs/capitalise")).isLessThan(calls.indexOf("/jobs/gl-close"));
        assertThat(counts.get("/jobs/capitalise").get()).isEqualTo(3); // 2 partitions + 1 retry
        assertThat(counts.get("/jobs/statements").get()).isEqualTo(4); // 1 + max_retries(3)

        JsonNode jobs = get("/v1/operations/eod-runs/" + runId + "/jobs?limit=50", TENANT).get("data");
        assertThat(jobs).hasSize(5);
        String statementsRun = null;
        for (JsonNode j : jobs) {
            if ("FAILED".equals(j.get("status").asString())) {
                statementsRun = j.get("id").asString();
                assertThat(j.get("attempt").asInt()).isEqualTo(4);
                assertThat(j.get("error_code").asString()).isEqualTo("upstream_error");
            } else {
                assertThat(j.get("status").asString()).isEqualTo("SUCCEEDED");
                assertThat(j.get("duration_ms").isNull()).isFalse();
            }
        }
        assertThat(statementsRun).isNotNull();

        // roll needs force + four-eyes; skipping needs four-eyes
        assertThat(postJson("/v1/operations/business-days/" + dayId + "/roll", "{}", 409).get("code").asString())
                .isEqualTo("eod_incomplete");
        HttpResponse<String> selfForce = send("POST", "/v1/operations/business-days/" + dayId + "/roll",
                "{\"forced\":true}", UUID.randomUUID().toString(), TENANT, "operator", null);
        assertThat(selfForce.statusCode()).isEqualTo(422);
        assertThat(json.readTree(selfForce.body()).get("code").asString()).isEqualTo("four_eyes_violation");
        HttpResponse<String> selfSkip = send("POST", "/v1/operations/eod-job-runs/" + statementsRun + "/skip",
                "{\"reason\":\"statements re-run tomorrow\"}", UUID.randomUUID().toString(), TENANT, "operator", null);
        assertThat(selfSkip.statusCode()).isEqualTo(422);

        HttpResponse<String> skip = send("POST", "/v1/operations/eod-job-runs/" + statementsRun + "/skip",
                "{\"reason\":\"statements re-run tomorrow\"}", UUID.randomUUID().toString(), TENANT, "supervisor", null);
        assertThat(skip.statusCode()).as(skip.body()).isEqualTo(200);
        assertThat(json.readTree(skip.body()).get("skipped_by").asString()).isEqualTo("supervisor");
        assertThat(awaitRun(runId).get("status").asString()).isEqualTo("SUCCEEDED");
    }

    @Test
    @Order(5)
    void rollMovesToNextBusinessDayOverWeekendAndHoliday() throws Exception {
        JsonNode roll = postJson("/v1/operations/business-days/" + dayId + "/roll", "{\"reason\":\"EOM done\"}", 200);
        assertThat(roll.get("from_business_date").asString()).isEqualTo("2026-01-30");
        assertThat(roll.get("to_business_date").asString()).isEqualTo("2026-02-03"); // Sat, Sun, Mon holiday
        assertThat(roll.get("forced").asBoolean()).isFalse();
        JsonNode old = get("/v1/operations/business-days/" + dayId, TENANT);
        assertThat(old.get("state").asString()).isEqualTo("CLOSED");
        assertThat(old.get("is_current").asBoolean()).isFalse();
        assertThat(old.get("next_business_date").asString()).isEqualTo("2026-02-03");
        JsonNode current = get("/v1/operations/business-days/current?legal_entity_id=" + ENTITY, TENANT);
        assertThat(current.get("business_date").asString()).isEqualTo("2026-02-03");
        assertThat(current.get("state").asString()).isEqualTo("OPEN");
        // no business date in the past may be opened again
        JsonNode back = postJson("/v1/operations/business-days/" + dayId + "/roll", "{}", 409);
        assertThat(back.get("code").asString()).isEqualTo("invalid_transition");
        assertThat(get("/v1/operations/date-rolls?legal_entity_id=" + ENTITY, TENANT).get("data")).hasSize(1);

        // reopen: same user who rolled is rejected, another user may reopen; then roll again
        HttpResponse<String> selfReopen = send("POST", "/v1/operations/business-days/" + dayId + "/reopen",
                "{\"reason\":\"fix accruals\"}", UUID.randomUUID().toString(), TENANT, "dev-user", null);
        assertThat(selfReopen.statusCode()).isEqualTo(422);
        HttpResponse<String> reopen = send("POST", "/v1/operations/business-days/" + dayId + "/reopen",
                "{\"reason\":\"fix accruals\"}", UUID.randomUUID().toString(), TENANT, "controller", null);
        assertThat(reopen.statusCode()).as(reopen.body()).isEqualTo(200);
        assertThat(json.readTree(reopen.body()).get("state").asString()).isEqualTo("OPEN");
        assertThat(get("/v1/operations/business-days/current?legal_entity_id=" + ENTITY, TENANT).get("id").asString())
                .isEqualTo(dayId);
    }

    @Test
    @Order(6)
    void failedCriticalJobBlocksRollAndRestartSkipsSucceededJobs() throws Exception {
        behaviour.clear();
        behaviour.put("/jobs/gl-close", n -> {
            throw new ApiException(422, "gl_unbalanced", "trial balance does not balance");
        });
        int accrualBefore = counts.get("/jobs/accrual").get();
        int glFirst = counts.get("/jobs/gl-close").get();
        JsonNode run = postJson("/v1/operations/business-days/" + dayId + "/start-eod", "{\"cycle_type\":\"EOD\"}", 200);
        String failedRun = run.get("id").asString();
        JsonNode done = awaitRun(failedRun);
        assertThat(done.get("status").asString()).isEqualTo("FAILED");
        assertThat(done.get("error").asString()).contains("GL_CLOSE");
        assertThat(get("/v1/operations/business-days/" + dayId, TENANT).get("state").asString()).isEqualTo("EOD_FAILED");
        assertThat(counts.get("/jobs/gl-close").get()).isEqualTo(glFirst + 1); // 422 is not retried
        assertThat(postJson("/v1/operations/business-days/" + dayId + "/roll", "{\"forced\":true}", 409).get("code").asString())
                .isEqualTo("eod_failed");
        assertThat(postJson("/v1/operations/eod-runs/" + failedRun + "/cancel", "{\"reason\":\"x\"}", 409).get("code").asString())
                .isEqualTo("invalid_transition");

        behaviour.clear();
        int glBefore = counts.get("/jobs/gl-close").get();
        JsonNode restart = postJson("/v1/operations/eod-runs/" + failedRun + "/restart", null, 200);
        assertThat(restart.get("is_restart").asBoolean()).isTrue();
        assertThat(restart.get("restart_of_id").asString()).isEqualTo(failedRun);
        JsonNode restarted = awaitRun(restart.get("id").asString());
        assertThat(restarted.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(counts.get("/jobs/accrual").get()).isEqualTo(accrualBefore + 1); // not re-run by the restart
        assertThat(counts.get("/jobs/gl-close").get()).isEqualTo(glBefore + 1);
        int carried = 0;
        for (JsonNode j : get("/v1/operations/eod-runs/" + restart.get("id").asString() + "/jobs", TENANT).get("data")) {
            if (j.path("checkpoint").has("carried_over_from")) {
                carried++;
            }
        }
        assertThat(carried).isEqualTo(4); // ACCRUAL, 2 CAPITALISE partitions, STATEMENTS
        // the old run can no longer be restarted
        assertThat(postJson("/v1/operations/eod-runs/" + failedRun + "/restart", null, 409).get("code").asString())
                .isEqualTo("not_latest_run");
    }

    @Test
    @Order(7)
    void lockPreventsDoubleExecution() throws Exception {
        JsonNode roll = postJson("/v1/operations/business-days/" + dayId + "/roll", "{}", 200);
        JsonNode current = get("/v1/operations/business-days/current?legal_entity_id=" + ENTITY, TENANT);
        assertThat(current.get("business_date").asString()).isEqualTo(roll.get("to_business_date").asString());
        String key = "eod:" + ENTITY + ":" + current.get("business_date").asString();
        TenantContext.callAs(UUID.fromString(TENANT), "other-instance",
                () -> lockStore.acquire(key, "other-instance", Duration.ofMinutes(5)));
        JsonNode locked = postJson("/v1/operations/business-days/" + current.get("id").asString() + "/start-eod", "{}", 409);
        assertThat(locked.get("code").asString()).isEqualTo("eod_locked");

        JsonNode locks = get("/v1/operations/batch-locks?lock_key=" + key, TENANT).get("data");
        assertThat(locks).hasSize(1);
        String lockId = locks.get(0).get("id").asString();
        assertThat(postJson("/v1/operations/batch-locks/" + lockId + "/force-release", "{}", 400).get("code").asString())
                .isEqualTo("validation_failed");
        HttpResponse<String> rel = send("POST", "/v1/operations/batch-locks/" + lockId + "/force-release",
                "{\"reason\":\"instance crashed\"}", UUID.randomUUID().toString(), TENANT, "ops", null);
        assertThat(rel.statusCode()).isEqualTo(204);
        assertThat(get("/v1/operations/batch-locks?lock_key=" + key, TENANT).get("data")).isEmpty();
        JsonNode run = postJson("/v1/operations/business-days/" + current.get("id").asString() + "/start-eod", "{}", 200);
        assertThat(awaitRun(run.get("id").asString()).get("status").asString()).isEqualTo("SUCCEEDED");
    }

    // ------------------------------------------------------------------ schedules

    @Test
    @Order(8)
    void schedulesComputeNextRunPauseResumeAndRunNow() throws Exception {
        JsonNode bad = postJson("/v1/operations/job-schedules", schedule("SWEEP_BAD", "not a cron", "UTC"), 422);
        assertThat(bad.get("code").asString()).isEqualTo("invalid_cron");
        JsonNode badZone = postJson("/v1/operations/job-schedules", schedule("SWEEP_TZ", "0 0 2 * * *", "Mars/Base"), 422);
        assertThat(badZone.get("code").asString()).isEqualTo("invalid_time_zone");

        JsonNode s = postJson("/v1/operations/job-schedules", schedule("SWEEP", "0 30 2 * * MON-FRI", "Europe/London"), 201);
        String id = s.get("id").asString();
        assertThat(s.get("next_run_at").isNull()).isFalse();
        java.time.ZonedDateTime next = java.time.OffsetDateTime.parse(s.get("next_run_at").asString())
                .atZoneSameInstant(java.time.ZoneId.of("Europe/London"));
        assertThat(next.getHour()).isEqualTo(2);
        assertThat(next.getMinute()).isEqualTo(30);
        assertThat(next.getDayOfWeek().getValue()).isLessThanOrEqualTo(5);

        JsonNode paused = postJson("/v1/operations/job-schedules/" + id + "/pause", null, 200);
        assertThat(paused.get("status").asString()).isEqualTo("PAUSED");
        assertThat(paused.has("next_run_at")).isFalse();
        assertThat(postJson("/v1/operations/job-schedules/" + id + "/pause", null, 409).get("code").asString())
                .isEqualTo("invalid_transition");
        JsonNode resumed = postJson("/v1/operations/job-schedules/" + id + "/resume", null, 200);
        assertThat(resumed.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(resumed.get("next_run_at").isNull()).isFalse();

        behaviour.put("/jobs/sweep", n -> ok(42));
        JsonNode run = postJson("/v1/operations/job-schedules/" + id + "/run-now", "{\"parameters\":{\"dry_run\":true}}", 200);
        assertThat(run.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(run.get("records_processed").asLong()).isEqualTo(42);
        JsonNode after = get("/v1/operations/job-schedules/" + id, TENANT);
        assertThat(after.get("last_run_status").asString()).isEqualTo("SUCCEEDED");
        assertThat(get("/v1/operations/job-schedules/" + id + "/runs", TENANT).get("data")).hasSize(1);
        assertThat(get("/v1/operations/batch-locks?lock_key=schedule:" + id, TENANT).get("data")).isEmpty();

        behaviour.put("/jobs/sweep", n -> {
            throw new ApiException(503, "upstream_error", "down");
        });
        JsonNode failed = postJson("/v1/operations/job-schedules/" + id + "/run-now", "{}", 200);
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        HttpResponse<String> del = send("DELETE", "/v1/operations/job-schedules/" + id, null, null, TENANT, "dev-user", null);
        assertThat(del.statusCode()).isEqualTo(409);

        HttpResponse<String> patch = send("PATCH", "/v1/operations/job-schedules/" + id,
                "{\"cron_expression\":\"0 0 23 * * *\",\"time_zone\":\"UTC\"}", null, TENANT, "dev-user",
                get("/v1/operations/job-schedules/" + id, TENANT).get("row_version").asString());
        assertThat(patch.statusCode()).as(patch.body()).isEqualTo(200);
        assertThat(java.time.OffsetDateTime.parse(json.readTree(patch.body()).get("next_run_at").asString())
                .atZoneSameInstant(java.time.ZoneOffset.UTC).getHour()).isEqualTo(23);
    }

    @Test
    @Order(9)
    void eventsAreWrittenToTheOutbox() {
        List<String> types = jdbc.sql("select distinct event_type from outbox").query(String.class).list();
        assertThat(types).contains("operations.business_day.opened", "operations.business_day.cut_off",
                "operations.business_day.closed", "operations.eod_run.started", "operations.eod_run.completed",
                "operations.eod_run.failed", "operations.eod_job_run.requested", "operations.eod_job_run.failed",
                "operations.date_roll.completed", "operations.job_schedule_run.completed");
    }

    @Test
    @Order(10)
    void otherTenantSeesNothing() throws Exception {
        assertThat(send("GET", "/v1/operations/business-days/" + dayId, null, null, OTHER_TENANT, "x", null).statusCode())
                .isEqualTo(404);
        assertThat(send("GET", "/v1/operations/business-days/current?legal_entity_id=" + ENTITY, null, null, OTHER_TENANT,
                "x", null).statusCode()).isEqualTo(404);
        assertThat(get("/v1/operations/eod-runs", OTHER_TENANT).get("data")).isEmpty();
        assertThat(send("POST", "/v1/operations/eod-runs/" + runId + "/restart", null, UUID.randomUUID().toString(),
                OTHER_TENANT, "x", null).statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ helpers

    private String jobDef(String code, String type, String endpoint, String deps, int parallelism, boolean critical,
                          String policy, int seq) throws Exception {
        return postJson("/v1/operations/eod-jobs", jobBody(code, type, endpoint, deps, parallelism, critical, policy, seq), 201)
                .get("id").asString();
    }

    private static String jobBody(String code, String type, String endpoint, String deps, int parallelism, boolean critical,
                                  String policy, int seq) {
        return """
                {"code":"%s","name":"%s","job_type":"%s","cycle_types":{"codes":["EOD"]},"owning_service":"deposit-service",
                 "endpoint":"%s","depends_on":{"codes":%s},"parallelism":%d,"is_critical":%s,"failure_policy":"%s",
                 "sequence":%d,"max_retries":3,"parameters":{"batch_size":500}}"""
                .formatted(code, code, type, endpoint, deps, parallelism, critical, policy, seq);
    }

    private static String schedule(String code, String cron, String zone) {
        return """
                {"code":"%s","name":"Sweep","owning_service":"deposit-service","endpoint":"/jobs/sweep",
                 "cron_expression":"%s","time_zone":"%s"}""".formatted(code, cron, zone);
    }

    private JsonNode awaitRun(String id) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        JsonNode run;
        do {
            run = get("/v1/operations/eod-runs/" + id, TENANT);
            String s = run.get("status").asString();
            if (!s.equals("RUNNING") && !s.equals("QUEUED")) {
                Thread.sleep(100); // let the finishing transaction's lock release settle
                return get("/v1/operations/eod-runs/" + id, TENANT);
            }
            Thread.sleep(50);
        } while (System.currentTimeMillis() < deadline);
        throw new AssertionError("run " + id + " did not finish: " + run);
    }

    private JsonNode postJson(String path, String body, int expected) throws Exception {
        HttpResponse<String> r = send("POST", path, body, UUID.randomUUID().toString(), TENANT, "dev-user", null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
    }

    private JsonNode get(String path, String tenant) throws Exception {
        HttpResponse<String> r = send("GET", path, null, null, tenant, "dev-user", null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
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
