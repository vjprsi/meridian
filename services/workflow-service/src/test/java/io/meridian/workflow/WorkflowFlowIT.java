package io.meridian.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import io.meridian.workflow.application.SweepService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end over HTTP against PostgreSQL 18 with row-level security: multi-level maker-checker approvals with
 * amount thresholds and four-eyes, definition publishing through approval, the workflow engine (human tasks,
 * service calls with retry, signals), task actions with SLA breach, expiry, idempotency and tenant isolation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.workflow.sweeper-enabled=false"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WorkflowFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000c1";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-0000000000c2";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    SweepService sweep;

    @MockitoBean
    ServiceClient services;

    final HttpClient http = HttpClient.newHttpClient();
    String paymentPolicy;
    String bigPayment;
    String definitionV1;
    String definitionV2;
    String reviewTask;
    String onboarding;

    @BeforeAll
    void setUp() throws Exception {
        paymentPolicy = post("/v1/approval-policies", """
                {"code":"PAY-RELEASE","name":"Payment release","resource_type":"payment","action":"release",
                 "approval_levels":[
                   {"level":1,"approver_roles":["CHECKER"],"required_approvals":1},
                   {"level":2,"approver_roles":["MANAGER"],"min_amount":"10000","required_approvals":1}]}""",
                "admin", "", 201).get("id").asString();
    }

    // ------------------------------------------------------------------ approval policies & requests

    @Test
    @Order(1)
    void policyLevelsAreValidated() throws Exception {
        JsonNode bad = post("/v1/approval-policies", """
                {"code":"BAD","name":"Bad","resource_type":"loan","action":"disburse",
                 "approval_levels":[{"level":2,"approver_roles":["X"]}]}""", "admin", "", 422);
        assertThat(bad.get("code").asString()).isEqualTo("invalid_approval_levels");
        JsonNode descending = post("/v1/approval-policies", """
                {"code":"BAD2","name":"Bad","resource_type":"loan","action":"disburse",
                 "approval_levels":[{"min_amount":"500"},{"min_amount":"100"}]}""", "admin", "", 422);
        assertThat(descending.get("code").asString()).isEqualTo("invalid_approval_levels");
        JsonNode empty = post("/v1/approval-policies", """
                {"code":"BAD4","name":"Bad","resource_type":"loan","action":"disburse","approval_levels":[]}""",
                "admin", "", 422);
        assertThat(empty.get("code").asString()).isEqualTo("invalid_approval_levels");
        JsonNode policy = get("/v1/approval-policies/" + paymentPolicy, TENANT, "admin", "");
        assertThat(policy.get("approval_levels").isArray()).isTrue();
        assertThat(policy.get("approval_levels")).hasSize(2);
        JsonNode missing = post("/v1/approval-policies", """
                {"code":"BAD3","name":"Bad","resource_type":"loan","action":"disburse"}""", "admin", "", 400);
        assertThat(missing.get("code").asString()).isEqualTo("validation_failed");
    }

    @Test
    @Order(2)
    void multiLevelApprovalWithFourEyes() throws Exception {
        JsonNode req = post("/v1/approval-requests", """
                {"resource_type":"payment","resource_id":"%s","action":"release","title":"Release wire 50k",
                 "payload":{"payment_id":"p-1"},"amount":"50000.00","currency":"USD","priority":"HIGH"}"""
                .formatted(UUID.randomUUID()), "alice", "", 201);
        bigPayment = req.get("id").asString();
        assertThat(req.get("status").asString()).isEqualTo("PENDING");
        assertThat(req.get("maker").asString()).isEqualTo("alice");
        assertThat(req.get("required_approvals").asInt()).isEqualTo(2);
        assertThat(req.get("approval_policy_id").asString()).isEqualTo(paymentPolicy);
        assertThat(req.get("expires_at").isNull()).isFalse();
        assertThat(req.get("approval_route").isArray()).isTrue();
        assertThat(req.get("approval_route")).hasSize(2);
        assertThat(req.get("approval_route").get(1).get("approver_roles").get(0).asString()).isEqualTo("MANAGER");
        assertThat(req.hasNonNull("execution_result")).isFalse();

        JsonNode self = post("/v1/approval-requests/" + bigPayment + "/approve", "{}", "alice", "CHECKER", 422);
        assertThat(self.get("code").asString()).isEqualTo("four_eyes_violation");
        JsonNode noRole = post("/v1/approval-requests/" + bigPayment + "/approve", "{}", "bob", "VIEWER", 403);
        assertThat(noRole.get("code").asString()).isEqualTo("not_authorized_approver");
        JsonNode noMfa = send("POST", "/v1/approval-requests/" + bigPayment + "/approve", "{}", key(), TENANT, "bob",
                "CHECKER", "false");
        assertThat(noMfa.get("code").asString()).isEqualTo("mfa_required");

        assertThat(ids(get("/v1/approval-requests/inbox", TENANT, "bob", "CHECKER"))).contains(bigPayment);
        assertThat(ids(get("/v1/approval-requests/inbox", TENANT, "alice", "CHECKER"))).doesNotContain(bigPayment);
        assertThat(ids(get("/v1/approval-requests/inbox", TENANT, "carol", "MANAGER"))).doesNotContain(bigPayment);

        JsonNode level1 = post("/v1/approval-requests/" + bigPayment + "/approve", "{\"comment\":\"ok\"}", "bob", "CHECKER,MANAGER", 200);
        assertThat(level1.get("status").asString()).isEqualTo("PENDING");
        assertThat(level1.get("current_level").asInt()).isEqualTo(2);
        assertThat(level1.get("approvals_received").asInt()).isEqualTo(1);

        JsonNode twice = post("/v1/approval-requests/" + bigPayment + "/approve", "{}", "bob", "CHECKER,MANAGER", 422);
        assertThat(twice.get("code").asString()).isEqualTo("four_eyes_violation");
        assertThat(ids(get("/v1/approval-requests/inbox", TENANT, "carol", "MANAGER"))).contains(bigPayment);

        String finalKey = key();
        JsonNode approved = postWithKey("/v1/approval-requests/" + bigPayment + "/approve", "{\"comment\":\"fine\"}",
                finalKey, "carol", "MANAGER", 200);
        assertThat(approved.get("status").asString()).isEqualTo("APPROVED");
        assertThat(approved.get("decided_at").isNull()).isFalse();

        // idempotent replay returns the stored response without a third decision
        HttpResponse<String> replay = raw("POST", "/v1/approval-requests/" + bigPayment + "/approve",
                "{\"comment\":\"fine\"}", finalKey, TENANT, "carol", "MANAGER", null);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        JsonNode decisions = get("/v1/approval-requests/" + bigPayment + "/decisions", TENANT, "carol", "").get("data");
        assertThat(decisions).hasSize(2);

        JsonNode again = post("/v1/approval-requests/" + bigPayment + "/approve", "{}", "dave", "MANAGER", 409);
        assertThat(again.get("code").asString()).isEqualTo("invalid_transition");
        assertThat(events(bigPayment)).containsExactly("platform.approval_request.created", "platform.approval_request.approved");
    }

    @Test
    @Order(3)
    void smallAmountNeedsOnlyFirstLevel() throws Exception {
        JsonNode req = post("/v1/approval-requests", """
                {"resource_type":"payment","action":"release","title":"Release 500","payload":{"payment_id":"p-2"},
                 "amount":"500","currency":"USD"}""", "alice", "", 201);
        assertThat(req.get("required_approvals").asInt()).isEqualTo(1);
        JsonNode approved = post("/v1/approval-requests/" + req.get("id").asString() + "/approve", "{}", "bob", "CHECKER", 200);
        assertThat(approved.get("status").asString()).isEqualTo("APPROVED");
    }

    @Test
    @Order(4)
    void rejectCancelAndRequestChanges() throws Exception {
        String r1 = newRequest("alice");
        JsonNode noComment = post("/v1/approval-requests/" + r1 + "/reject", "{}", "bob", "CHECKER", 400);
        assertThat(noComment.get("code").asString()).isEqualTo("validation_failed");
        JsonNode ownReject = post("/v1/approval-requests/" + r1 + "/reject", "{\"comment\":\"no\"}", "alice", "CHECKER", 422);
        assertThat(ownReject.get("code").asString()).isEqualTo("four_eyes_violation");
        JsonNode rejected = post("/v1/approval-requests/" + r1 + "/reject", "{\"comment\":\"wrong beneficiary\"}", "bob", "CHECKER", 200);
        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
        JsonNode cancelRejected = post("/v1/approval-requests/" + r1 + "/cancel", "{}", "alice", "", 409);
        assertThat(cancelRejected.get("code").asString()).isEqualTo("invalid_transition");

        String r2 = newRequest("alice");
        JsonNode notMaker = post("/v1/approval-requests/" + r2 + "/cancel", "{\"reason\":\"x\"}", "bob", "CHECKER", 403);
        assertThat(notMaker.get("code").asString()).isEqualTo("not_maker");
        JsonNode cancelled = post("/v1/approval-requests/" + r2 + "/cancel", "{\"reason\":\"duplicate\"}", "alice", "", 200);
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("cancel_reason").asString()).isEqualTo("duplicate");
        assertThat(events(r2)).containsExactly("platform.approval_request.created", "platform.approval_request.cancelled");

        String r3 = newRequest("alice");
        JsonNode changes = post("/v1/approval-requests/" + r3 + "/request-changes", "{\"comment\":\"attach invoice\"}",
                "bob", "CHECKER", 200);
        assertThat(changes.get("status").asString()).isEqualTo("CHANGES_REQUESTED");
        assertThat(changes.get("decided_at").isNull()).isFalse();
        JsonNode d = get("/v1/approval-requests/" + r3 + "/decisions", TENANT, "bob", "").get("data").get(0);
        assertThat(d.get("decision").asString()).isEqualTo("REQUEST_CHANGES");
        post("/v1/approval-requests/" + r3 + "/approve", "{}", "carol", "CHECKER", 409);
        assertThat(events(r1)).contains("platform.approval_request.rejected");
        assertThat(events(r3)).containsExactly("platform.approval_request.created",
                "platform.approval_request.changes_requested");
    }

    @Test
    @Order(5)
    void requestValidationAndIdempotency() throws Exception {
        JsonNode noCcy = post("/v1/approval-requests", """
                {"resource_type":"payment","action":"release","title":"x","payload":{},"amount":"10"}""", "alice", "", 422);
        assertThat(noCcy.get("code").asString()).isEqualTo("currency_required");
        JsonNode noPayload = post("/v1/approval-requests", """
                {"resource_type":"payment","action":"release","title":"x"}""", "alice", "", 400);
        assertThat(noPayload.get("code").asString()).isEqualTo("validation_failed");

        String k = key();
        String body = """
                {"resource_type":"config_entry","action":"update","title":"Change limit","payload":{"k":"v"}}""";
        JsonNode first = postWithKey("/v1/approval-requests", body, k, "alice", "", 201);
        HttpResponse<String> replay = raw("POST", "/v1/approval-requests", body, k, TENANT, "alice", "", null);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(replay.body()).get("id").asString()).isEqualTo(first.get("id").asString());
        JsonNode reused = postWithKey("/v1/approval-requests", body.replace("Change limit", "Other"), k, "alice", "", 422);
        assertThat(reused.get("code").asString()).isEqualTo("idempotency_key_reused");
        // no policy for config_entry/update: default single level
        assertThat(first.get("required_approvals").asInt()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ definitions & engine

    static final String DEFINITION = """
            {"code":"retail-onboarding","name":"Retail onboarding",
             "input_schema":{"required":["account_ref"]},
             "definition":{"start":"review","steps":[
               {"id":"review","type":"TASK","task":{"task_type":"KYC_REVIEW","title":"Review applicant","queue":"kyc",
                 "candidate_roles":["KYC_OFFICER"],"sla_hours":4,"priority":"HIGH"},
                "transitions":{"APPROVED":"book","REJECTED":"declined"}},
               {"id":"book","type":"SERVICE","call":{"service":"deposit-service","method":"POST",
                 "path":"/v1/deposit-accounts/{account_ref}/activate","body":{"reason":"onboarding"}},
                "transitions":{"next":"risk"}},
               {"id":"risk","type":"DECISION","variable":"risk","transitions":{"HIGH":"wait_docs","default":"done"}},
               {"id":"wait_docs","type":"WAIT","transitions":{"documents_received":"done"}},
               {"id":"done","type":"END"},
               {"id":"declined","type":"END","outcome":"DECLINED"}]}}""";

    @Test
    @Order(10)
    void definitionsAreValidatedVersionedAndPublishedThroughApproval() throws Exception {
        JsonNode invalid = post("/v1/workflows/definitions", """
                {"code":"bad","name":"Bad","definition":{"start":"a","steps":[{"id":"a","type":"WAIT","transitions":{"x":"nowhere"}}]}}""",
                "alice", "", 422);
        assertThat(invalid.get("code").asString()).isEqualTo("invalid_definition");

        JsonNode v1 = post("/v1/workflows/definitions", DEFINITION, "alice", "", 201);
        definitionV1 = v1.get("id").asString();
        assertThat(v1.get("version").asInt()).isEqualTo(1);
        assertThat(v1.get("status").asString()).isEqualTo("DRAFT");
        // status is read-only: a client-supplied value is ignored
        JsonNode v2 = post("/v1/workflows/definitions",
                DEFINITION.replace("\"name\":\"Retail onboarding\",", "\"name\":\"Retail onboarding\",\"status\":\"PUBLISHED\","),
                "alice", "", 201);
        definitionV2 = v2.get("id").asString();
        assertThat(v2.get("version").asInt()).isEqualTo(2);
        assertThat(v2.get("status").asString()).isEqualTo("DRAFT");

        JsonNode notPublished = post("/v1/workflows/instances", """
                {"workflow_definition_id":"%s","variables":{"account_ref":"ACC-1"}}""".formatted(definitionV1), "alice", "", 422);
        assertThat(notPublished.get("code").asString()).isEqualTo("definition_not_published");
        JsonNode deprecateDraft = post("/v1/workflows/definitions/" + definitionV1 + "/deprecate", null, "alice", "", 409);
        assertThat(deprecateDraft.get("code").asString()).isEqualTo("invalid_transition");

        JsonNode approval = post("/v1/workflows/definitions/" + definitionV1 + "/publish", null, "alice", "", 200);
        assertThat(approval.get("resource_type").asString()).isEqualTo("workflow_definition");
        assertThat(approval.get("resource_id").asString()).isEqualTo(definitionV1);
        assertThat(approval.get("status").asString()).isEqualTo("PENDING");
        JsonNode duplicate = post("/v1/workflows/definitions/" + definitionV1 + "/publish", null, "alice", "", 409);
        assertThat(duplicate.get("code").asString()).isEqualTo("approval_already_pending");

        JsonNode self = post("/v1/approval-requests/" + approval.get("id").asString() + "/approve", "{}", "alice", "", 422);
        assertThat(self.get("code").asString()).isEqualTo("four_eyes_violation");
        JsonNode executed = post("/v1/approval-requests/" + approval.get("id").asString() + "/approve", "{}", "bob", "", 200);
        assertThat(executed.get("status").asString()).isEqualTo("EXECUTED");
        assertThat(executed.get("execution_result").get("result").get("status").asString()).isEqualTo("PUBLISHED");
        assertThat(get("/v1/workflows/definitions/" + definitionV1, TENANT, "alice", "").get("status").asString())
                .isEqualTo("PUBLISHED");
        assertThat(events(approval.get("id").asString())).contains("platform.approval_request.executed");

        HttpResponse<String> patchPublished = raw("PATCH", "/v1/workflows/definitions/" + definitionV1,
                "{\"name\":\"Renamed\"}", null, TENANT, "alice", "", "1");
        assertThat(patchPublished.statusCode()).isEqualTo(409);
        HttpResponse<String> patchDraft = raw("PATCH", "/v1/workflows/definitions/" + definitionV2,
                "{\"name\":\"Retail onboarding v2\"}", null, TENANT, "alice", "", "1");
        assertThat(patchDraft.statusCode()).as(patchDraft.body()).isEqualTo(200);
    }

    @Test
    @Order(11)
    void instanceRunsTaskThenServiceCallFailsAndRetries() throws Exception {
        JsonNode missingInput = post("/v1/workflows/instances", """
                {"workflow_definition_id":"%s","variables":{}}""".formatted(definitionV1), "alice", "", 422);
        assertThat(missingInput.get("code").asString()).isEqualTo("missing_input");

        JsonNode inst = post("/v1/workflows/instances", """
                {"workflow_definition_id":"%s","business_key":"APP-1","subject_type":"CUSTOMER","subject_id":"%s",
                 "variables":{"account_ref":"ACC-1","risk":"LOW"}}""".formatted(definitionV1, UUID.randomUUID()),
                "alice", "", 201);
        onboarding = inst.get("id").asString();
        assertThat(inst.get("status").asString()).isEqualTo("WAITING");
        assertThat(inst.get("current_step").asString()).isEqualTo("review");

        JsonNode tasks = get("/v1/tasks?queue=kyc&status=OPEN", TENANT, "alice", "").get("data");
        assertThat(tasks).hasSize(1);
        JsonNode task = tasks.get(0);
        reviewTask = task.get("id").asString();
        assertThat(task.get("workflow_instance_id").asString()).isEqualTo(onboarding);
        assertThat(task.get("priority").asString()).isEqualTo("HIGH");
        assertThat(task.get("due_at").isNull()).isFalse();
        assertThat(task.get("candidate_roles").isArray()).isTrue();
        assertThat(task.get("candidate_roles").get(0).asString()).isEqualTo("KYC_OFFICER");

        JsonNode notCandidate = post("/v1/tasks/" + reviewTask + "/claim", null, "dave", "TELLER", 403);
        assertThat(notCandidate.get("code").asString()).isEqualTo("not_candidate");
        JsonNode notClaimed = post("/v1/tasks/" + reviewTask + "/complete", "{\"outcome\":\"APPROVED\"}", "dave", "KYC_OFFICER", 409);
        assertThat(notClaimed.get("code").asString()).isEqualTo("task_not_claimed");
        JsonNode claimed = post("/v1/tasks/" + reviewTask + "/claim", null, "dave", "KYC_OFFICER", 200);
        assertThat(claimed.get("status").asString()).isEqualTo("CLAIMED");
        assertThat(claimed.get("assignee").asString()).isEqualTo("dave");
        JsonNode taken = post("/v1/tasks/" + reviewTask + "/claim", null, "erin", "KYC_OFFICER", 409);
        assertThat(taken.get("code").asString()).isEqualTo("task_already_claimed");
        JsonNode notAssignee = post("/v1/tasks/" + reviewTask + "/complete", "{\"outcome\":\"APPROVED\"}", "erin", "KYC_OFFICER", 403);
        assertThat(notAssignee.get("code").asString()).isEqualTo("not_task_assignee");
        JsonNode badOutcome = post("/v1/tasks/" + reviewTask + "/complete", "{\"outcome\":\"MAYBE\"}", "dave", "KYC_OFFICER", 422);
        assertThat(badOutcome.get("code").asString()).isEqualTo("invalid_outcome");
        assertThat(get("/v1/tasks/" + reviewTask, TENANT, "dave", "").get("status").asString()).isEqualTo("CLAIMED");

        when(services.post(eq("deposit-service"), eq("/v1/deposit-accounts/ACC-1/activate"), any(), anyString()))
                .thenThrow(new ApiException(502, "upstream_error", "deposit-service is not reachable"))
                .thenReturn(json.readTree("{\"status\":\"ACTIVE\"}"));
        JsonNode completed = post("/v1/tasks/" + reviewTask + "/complete",
                "{\"outcome\":\"APPROVED\",\"form_data\":{\"notes\":\"docs fine\"}}", "dave", "KYC_OFFICER", 200);
        assertThat(completed.get("status").asString()).isEqualTo("COMPLETED");
        JsonNode failed = get("/v1/workflows/instances/" + onboarding, TENANT, "alice", "");
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(failed.get("current_step").asString()).isEqualTo("book");
        assertThat(failed.get("error").asString()).contains("upstream_error");
        assertThat(failed.get("variables").get("review").get("outcome").asString()).isEqualTo("APPROVED");

        JsonNode signalFailed = post("/v1/workflows/instances/" + onboarding + "/signal", "{\"signal\":\"x\"}", "alice", "", 409);
        assertThat(signalFailed.get("code").asString()).isEqualTo("invalid_transition");
        JsonNode retried = post("/v1/workflows/instances/" + onboarding + "/retry", null, "alice", "", 200);
        assertThat(retried.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(retried.get("completed_at").isNull()).isFalse();
        assertThat(retried.get("variables").get("book").get("status").asString()).isEqualTo("ACTIVE");
        assertThat(retried.get("outcome").asString()).isEqualTo("COMPLETED");
        assertThat(retried.get("variables").has("_engine")).isFalse();
        assertThat(retried.has("engine_state")).isFalse();
        JsonNode retryAgain = post("/v1/workflows/instances/" + onboarding + "/retry", null, "alice", "", 409);
        assertThat(retryAgain.get("code").asString()).isEqualTo("invalid_transition");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(services, times(2)).post(eq("deposit-service"), eq("/v1/deposit-accounts/ACC-1/activate"), any(), keys.capture());
        assertThat(keys.getAllValues().get(0)).isEqualTo(keys.getAllValues().get(1));
        assertThat(events(onboarding)).containsExactly("platform.workflow_instance.failed", "platform.workflow_instance.completed");
        assertThat(events(reviewTask)).containsExactly("platform.work_task.created", "platform.work_task.completed");
    }

    @Test
    @Order(12)
    void signalsAndCancellation() throws Exception {
        when(services.post(eq("deposit-service"), eq("/v1/deposit-accounts/ACC-2/activate"), any(), anyString()))
                .thenReturn(json.readTree("{\"status\":\"ACTIVE\"}"));
        String inst = post("/v1/workflows/instances", """
                {"workflow_definition_id":"%s","variables":{"account_ref":"ACC-2","risk":"HIGH"}}""".formatted(definitionV1),
                "alice", "", 201).get("id").asString();
        JsonNode waitingOnTask = post("/v1/workflows/instances/" + inst + "/signal", "{\"signal\":\"documents_received\"}", "alice", "", 422);
        assertThat(waitingOnTask.get("code").asString()).isEqualTo("unknown_signal");
        // a signal can also drive a TASK step; the open task is cancelled
        JsonNode afterApproval = post("/v1/workflows/instances/" + inst + "/signal", "{\"signal\":\"APPROVED\"}", "alice", "", 200);
        assertThat(afterApproval.get("status").asString()).isEqualTo("WAITING");
        assertThat(afterApproval.get("current_step").asString()).isEqualTo("wait_docs");
        JsonNode done = post("/v1/workflows/instances/" + inst + "/signal",
                "{\"signal\":\"documents_received\",\"data\":{\"docs\":3}}", "alice", "", 200);
        assertThat(done.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(done.get("variables").get("docs").asInt()).isEqualTo(3);
        JsonNode closed = post("/v1/workflows/instances/" + inst + "/signal", "{\"signal\":\"documents_received\"}", "alice", "", 409);
        assertThat(closed.get("code").asString()).isEqualTo("invalid_transition");

        String toCancel = post("/v1/workflows/instances", """
                {"workflow_definition_id":"%s","variables":{"account_ref":"ACC-3"}}""".formatted(definitionV1),
                "alice", "", 201).get("id").asString();
        JsonNode cancelled = post("/v1/workflows/instances/" + toCancel + "/cancel", "{\"reason\":\"customer withdrew\"}", "alice", "", 200);
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        JsonNode tasks = get("/v1/tasks?status=CANCELLED", TENANT, "alice", "").get("data");
        assertThat(tasks).anySatisfy(t -> assertThat(t.get("workflow_instance_id").asString()).isEqualTo(toCancel));
        post("/v1/workflows/instances/" + toCancel + "/cancel", "{\"reason\":\"again\"}", "alice", "", 409);
        assertThat(events(toCancel)).containsExactly("platform.workflow_instance.cancelled");
    }

    @Test
    @Order(13)
    void taskActionsPatchGuardAndSlaBreach() throws Exception {
        String inst = post("/v1/workflows/instances", """
                {"workflow_definition_id":"%s","variables":{"account_ref":"ACC-4"}}""".formatted(definitionV1),
                "alice", "", 201).get("id").asString();
        JsonNode task = get("/v1/tasks?status=OPEN&queue=kyc", TENANT, "alice", "").get("data").get(0);
        String id = task.get("id").asString();
        assertThat(task.get("workflow_instance_id").asString()).isEqualTo(inst);

        JsonNode assigned = post("/v1/tasks/" + id + "/assign", "{\"assignee\":\"frank\"}", "lead", "", 200);
        assertThat(assigned.get("assignee").asString()).isEqualTo("frank");
        JsonNode releaseOther = post("/v1/tasks/" + id + "/release", null, "dave", "", 403);
        assertThat(releaseOther.get("code").asString()).isEqualTo("not_task_assignee");
        JsonNode released = post("/v1/tasks/" + id + "/release", null, "frank", "", 200);
        assertThat(released.get("status").asString()).isEqualTo("OPEN");
        assertThat(released.hasNonNull("assignee")).isFalse();
        JsonNode escalated = post("/v1/tasks/" + id + "/escalate", "{\"reason\":\"VIP customer waiting\"}", "frank", "", 200);
        assertThat(escalated.get("status").asString()).isEqualTo("ESCALATED");
        assertThat(escalated.get("priority").asString()).isEqualTo("URGENT");
        assertThat(escalated.get("escalation_reason").asString()).isEqualTo("VIP customer waiting");
        assertThat(events(id)).contains("platform.work_task.escalated");
        post("/v1/tasks/" + id + "/escalate", "{\"reason\":\"again\"}", "frank", "", 409);

        int version = get("/v1/tasks/" + id, TENANT, "alice", "").get("row_version").asInt();
        // status and outcome are read-only: a PATCH silently ignores them
        HttpResponse<String> patchStatus = raw("PATCH", "/v1/tasks/" + id,
                "{\"status\":\"COMPLETED\",\"outcome\":\"APPROVED\",\"due_at\":\"2020-01-01T00:00:00Z\"}", null, TENANT,
                "alice", "", String.valueOf(version));
        assertThat(patchStatus.statusCode()).as(patchStatus.body()).isEqualTo(200);
        JsonNode patched = json.readTree(patchStatus.body());
        assertThat(patched.get("status").asString()).isEqualTo("ESCALATED");
        assertThat(patched.hasNonNull("outcome")).isFalse();
        HttpResponse<String> patchAssignee = raw("PATCH", "/v1/tasks/" + id, "{\"assignee\":\"zed\"}", null,
                TENANT, "alice", "", String.valueOf(patched.get("row_version").asInt()));
        assertThat(patchAssignee.statusCode()).isEqualTo(422);

        SweepService.Result r = TenantContext.callAs(UUID.fromString(TENANT), "system", sweep::processDue);
        assertThat(r.breachedTasks()).isGreaterThanOrEqualTo(1);
        assertThat(get("/v1/tasks/" + id, TENANT, "alice", "").get("sla_breached").asBoolean()).isTrue();
        assertThat(events(id)).contains("platform.work_task.sla_breached");
    }

    @Test
    @Order(15)
    void tasksCanBeCreatedDirectly() throws Exception {
        JsonNode task = post("/v1/tasks", """
                {"task_type":"PAYMENT_REPAIR","title":"Repair payment","queue":"payments-ops",
                 "candidate_roles":["OPS"],"priority":"HIGH","status":"COMPLETED","outcome":"DONE",
                 "workflow_instance_id":"%s"}""".formatted(UUID.randomUUID()), "alice", "", 201);
        String id = task.get("id").asString();
        assertThat(task.get("status").asString()).isEqualTo("OPEN");
        assertThat(task.hasNonNull("outcome")).isFalse();
        assertThat(task.hasNonNull("workflow_instance_id")).isFalse();
        assertThat(task.get("candidate_roles").get(0).asString()).isEqualTo("OPS");
        assertThat(events(id)).containsExactly("platform.work_task.created");

        JsonNode notCandidate = post("/v1/tasks/" + id + "/claim", null, "gus", "TELLER", 403);
        assertThat(notCandidate.get("code").asString()).isEqualTo("not_candidate");
        post("/v1/tasks/" + id + "/claim", null, "gus", "OPS", 200);
        JsonNode done = post("/v1/tasks/" + id + "/complete", "{\"outcome\":\"REPAIRED\"}", "gus", "OPS", 200);
        assertThat(done.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(done.get("outcome").asString()).isEqualTo("REPAIRED");

        JsonNode assigned = post("/v1/tasks", """
                {"task_type":"REVIEW","title":"Review","queue":"ops","assignee":"hank"}""", "alice", "", 201);
        assertThat(assigned.get("status").asString()).isEqualTo("CLAIMED");
        JsonNode missing = post("/v1/tasks", "{\"title\":\"x\"}", "alice", "", 400);
        assertThat(missing.get("code").asString()).isEqualTo("validation_failed");
    }

    @Test
    @Order(14)
    void pendingRequestsExpire() throws Exception {
        String id = newRequest("alice");
        TenantContext.callAs(UUID.fromString(TENANT), "test", () -> jdbc.sql(
                "update approval_request set expires_at = now() - interval '1 minute' where id = ?::uuid")
                .param(id).update());
        JsonNode late = post("/v1/approval-requests/" + id + "/approve", "{}", "bob", "CHECKER", 409);
        assertThat(late.get("code").asString()).isEqualTo("approval_request_expired");
        SweepService.Result r = TenantContext.callAs(UUID.fromString(TENANT), "system", sweep::processDue);
        assertThat(r.expiredRequests()).isGreaterThanOrEqualTo(1);
        assertThat(get("/v1/approval-requests/" + id, TENANT, "alice", "").get("status").asString()).isEqualTo("EXPIRED");
        assertThat(events(id)).contains("platform.approval_request.expired");
    }

    @Test
    @Order(20)
    void otherTenantSeesNothing() throws Exception {
        HttpResponse<String> r = raw("GET", "/v1/approval-requests/" + bigPayment, null, null, OTHER_TENANT, "bob", "CHECKER", null);
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(get("/v1/approval-requests/inbox", OTHER_TENANT, "bob", "CHECKER").get("data")).isEmpty();
        assertThat(get("/v1/tasks", OTHER_TENANT, "bob", "").get("data")).isEmpty();
        HttpResponse<String> approve = raw("POST", "/v1/approval-requests/" + bigPayment + "/approve", "{}", key(),
                OTHER_TENANT, "zed", "MANAGER", null);
        assertThat(approve.statusCode()).isEqualTo(404);
        HttpResponse<String> claim = raw("POST", "/v1/tasks/" + reviewTask + "/claim", null, key(), OTHER_TENANT, "zed", "", null);
        assertThat(claim.statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ helpers

    private String newRequest(String maker) throws Exception {
        return post("/v1/approval-requests", """
                {"resource_type":"payment","action":"release","title":"Release","payload":{"payment_id":"%s"},
                 "amount":"100","currency":"USD"}""".formatted(UUID.randomUUID()), maker, "", 201).get("id").asString();
    }

    private List<String> events(String aggregateId) {
        return jdbc.sql("select event_type from outbox where aggregate_id = ?::uuid order by created_at, id")
                .param(aggregateId).query(String.class).list();
    }

    private static List<String> ids(JsonNode page) {
        List<String> out = new java.util.ArrayList<>();
        page.get("data").forEach(n -> out.add(n.get("id").asString()));
        return out;
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private JsonNode post(String path, String body, String user, String roles, int expected) throws Exception {
        return postWithKey(path, body, key(), user, roles, expected);
    }

    private JsonNode postWithKey(String path, String body, String key, String user, String roles, int expected) throws Exception {
        HttpResponse<String> r = raw("POST", path, body, key, TENANT, user, roles, null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path, String tenant, String user, String roles) throws Exception {
        HttpResponse<String> r = raw("GET", path, null, null, tenant, user, roles, null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private JsonNode send(String method, String path, String body, String key, String tenant, String user, String roles,
                          String mfa) throws Exception {
        HttpRequest.Builder b = request(method, path, body, key, tenant, user, roles, null);
        b.header("X-MFA-Verified", mfa);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return json.readTree(r.body());
    }

    private HttpResponse<String> raw(String method, String path, String body, String key, String tenant, String user,
                                     String roles, String ifMatch) throws Exception {
        return http.send(request(method, path, body, key, tenant, user, roles, ifMatch).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String method, String path, String body, String key, String tenant, String user,
                                        String roles, String ifMatch) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", tenant).header("X-User", user).header("Content-Type", "application/json");
        if (roles != null && !roles.isEmpty()) {
            b.header("X-Roles", roles);
        }
        if (key != null) {
            b.header("Idempotency-Key", key);
        }
        if (ifMatch != null) {
            b.header("If-Match", ifMatch);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return b;
    }
}
