package io.meridian.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.meridian.approvals.ApprovalDecision.Outcome;
import io.meridian.approvals.ApprovalEventsDispatcher;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.identity.application.AccessReviewScheduler;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * GAP-13 access recertification: the report (users x roles x last sign-in, privileged holders, dormant accounts), a
 * quarterly review snapshotting every active assignment, attest / revoke by a reviewer (never on their own access),
 * submission to central approvals, four-eyes on the decision, rework after a rejection, and the approved revocations
 * applied exactly once.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true"})
@Import(MeridianTestContainers.class)
class AccessReviewIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000e3";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApprovalEventsDispatcher dispatcher;

    @Autowired
    AccessReviewScheduler scheduler;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    final AtomicReference<ObjectNode> lastApproval = new AtomicReference<>();

    @BeforeEach
    void stubWorkflow() {
        when(serviceClient.post(eq("workflow-service"), eq("/v1/approval-requests"), any(), anyString())).thenAnswer(inv -> {
            ObjectNode row = json.valueToTree(inv.getArgument(2));
            row.put("id", UUID.randomUUID().toString());
            row.put("status", "PENDING");
            row.put("maker", TenantContext.principal());
            lastApproval.set(row);
            return row;
        });
    }

    @Test
    void quarterlyReviewAttestsRevokesAndAppliesAfterASecondPersonApproves() throws Exception {
        String anna = user("anna", 1);
        String bob = user("bob", 200);                                         // no sign-in for 200 days: dormant
        String teller = post("/v1/roles", "{\"code\":\"AR_TELLER\",\"name\":\"Teller\"}", 201, "admin").get("id").asString();
        String fraudAdmin = post("/v1/roles", "{\"code\":\"AR_FRAUD_ADMIN\",\"name\":\"Fraud admin\",\"is_privileged\":true,"
                + "\"max_session_minutes\":60}", 201, "admin").get("id").asString();
        String annaTeller = assign(anna, teller);
        String bobTeller = assign(bob, teller);
        // a standing privileged assignment (e.g. migrated from a legacy system): exactly what a review must surface
        String annaFraud = TenantContext.callAs(UUID.fromString(TENANT), "migration", () -> jdbc.sql("""
                insert into user_role (tenant_id, user_id, role_id, scope_type, granted_by)
                values (?::uuid, ?::uuid, ?::uuid, 'TENANT', 'migration') returning id::text""")
                .params(TENANT, anna, fraudAdmin).query(String.class).single());

        JsonNode report = get("/v1/access-reviews/report?dormant_days=90");
        assertThat(report.get("totals").get("privileged_assignments").asInt()).isEqualTo(1);
        assertThat(report.get("privileged_role_holders").get(0).get("role_code").asString()).isEqualTo("AR_FRAUD_ADMIN");
        assertThat(report.get("privileged_role_holders").get(0).get("standing").asBoolean()).isTrue();
        assertThat(report.get("dormant_accounts").findValuesAsString("username")).containsExactly("bob");
        JsonNode annaRow = find(report.get("users"), "username", "anna");
        assertThat(annaRow.get("roles")).hasSize(2);
        assertThat(annaRow.has("email")).isFalse();                         // no contact data in the report

        JsonNode review = post("/v1/access-reviews", "{\"period\":\"2026-Q4\",\"due_date\":\"2026-11-06\"}", 201, "rita");
        String id = review.get("id").asString();
        assertThat(review.get("status").asString()).isEqualTo("OPEN");
        assertThat(review.get("item_count").asInt()).isEqualTo(3);
        assertThat(review.get("privileged_count").asInt()).isEqualTo(1);
        assertThat(review.get("dormant_count").asInt()).isEqualTo(1);
        post("/v1/access-reviews", "{\"period\":\"2026-Q4\",\"due_date\":\"2026-11-06\"}", 409, "rita");

        JsonNode items = get("/v1/access-reviews/" + id + "/items").get("data");
        assertThat(items).hasSize(3);
        String annaTellerItem = item(items, annaTeller);
        String annaFraudItem = item(items, annaFraud);
        String bobTellerItem = item(items, bobTeller);

        // nothing decided yet: cannot submit; nobody reviews their own access; a revocation needs a comment
        post("/v1/access-reviews/" + id + "/submit", null, 422, "rita");
        assertThat(post("/v1/access-reviews/" + id + "/decisions", decisions(annaTellerItem, "ATTEST", null), 422, "anna")
                .get("code").asString()).isEqualTo("self_review");
        assertThat(post("/v1/access-reviews/" + id + "/decisions", decisions(bobTellerItem, "REVOKE", null), 422, "rita")
                .get("code").asString()).isEqualTo("comment_required");

        post("/v1/access-reviews/" + id + "/decisions", "{\"decisions\":["
                + "{\"item_id\":\"" + annaTellerItem + "\",\"decision\":\"ATTEST\"},"
                + "{\"item_id\":\"" + annaFraudItem + "\",\"decision\":\"REVOKE\",\"comment\":\"standing privilege\"},"
                + "{\"item_id\":\"" + bobTellerItem + "\",\"decision\":\"REVOKE\",\"comment\":\"dormant account\"}]}", 200, "rita");
        JsonNode approval = post("/v1/access-reviews/" + id + "/submit", null, 200, "rita");
        assertThat(approval.get("resource_type").asString()).isEqualTo("access_review");
        assertThat(approval.get("action").asString()).isEqualTo("complete");
        assertThat(approval.get("payload").get("revoked").asInt()).isEqualTo(2);
        assertThat(approval.get("payload").get("privileged_revoked").asInt()).isEqualTo(1);
        assertThat(get("/v1/access-reviews/" + id).get("status").asString()).isEqualTo("SUBMITTED");

        // the reviewer approving their own decisions: four-eyes refuses, nothing is revoked
        dispatcher.dispatch(UUID.fromString(TENANT), decision(approval, "rita"), Outcome.APPROVED);
        assertThat(get("/v1/access-reviews/" + id).get("status").asString()).isEqualTo("SUBMITTED");
        assertThat(activeAssignments(anna)).hasSize(2);

        // the checker sends it back: the review is open again for rework
        dispatcher.dispatch(UUID.fromString(TENANT), decision(approval, "carl"), Outcome.REJECTED);
        JsonNode reopened = get("/v1/access-reviews/" + id);
        assertThat(reopened.get("status").asString()).isEqualTo("OPEN");
        assertThat(reopened.get("rejection_reason").asString()).contains("REJECTED");
        assertThat(activeAssignments(bob)).hasSize(1);

        JsonNode second = post("/v1/access-reviews/" + id + "/submit", null, 200, "rita");
        dispatcher.dispatch(UUID.fromString(TENANT), decision(second, "carl"), Outcome.APPROVED);
        JsonNode done = get("/v1/access-reviews/" + id);
        assertThat(done.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(done.get("decided_by").asString()).isEqualTo("carl");
        assertThat(done.get("attested_count").asInt()).isEqualTo(1);
        assertThat(done.get("revoked_count").asInt()).isEqualTo(2);
        assertThat(activeAssignments(anna)).containsExactly(teller);
        assertThat(activeAssignments(bob)).isEmpty();
        JsonNode after = get("/v1/access-reviews/" + id + "/items?decision=REVOKE").get("data");
        assertThat(after).hasSize(2).allSatisfy(i -> assertThat(i.hasNonNull("revoked_at")).isTrue());

        // redelivery of the approval is a no-op; a completed review takes no more decisions
        dispatcher.dispatch(UUID.fromString(TENANT), decision(second, "carl"), Outcome.APPROVED);
        assertThat(get("/v1/access-reviews/" + id).get("status").asString()).isEqualTo("COMPLETED");
        post("/v1/access-reviews/" + id + "/decisions", decisions(annaTellerItem, "REVOKE", "x"), 409, "rita");
    }

    @Test
    void theSchedulerOpensEachQuartersReviewOnce() throws Exception {
        String tenant = "0199a000-0000-7000-8000-0000000000e4";
        assertThat(scheduler.openFor(UUID.fromString(tenant), LocalDate.parse("2027-01-01"))).isTrue();
        assertThat(scheduler.openFor(UUID.fromString(tenant), LocalDate.parse("2027-02-15"))).isFalse();
        HttpResponse<String> r = send("GET", "/v1/access-reviews?period=2027-Q1", null, "viewer", tenant);
        JsonNode page = json.readTree(r.body());
        assertThat(page.get("data")).hasSize(1);
        assertThat(page.get("data").get(0).get("due_date").asString()).isEqualTo("2027-01-31");
        assertThat(page.get("data").get(0).get("created_by").asString()).isEqualTo("job:access-review");
    }

    private String user(String username, int daysSinceLogin) throws Exception {
        String id = post("/v1/users", "{\"username\":\"" + username + "\",\"email\":\"" + username + "@bank.example\","
                + "\"first_name\":\"F\",\"last_name\":\"L\"}", 201, "admin").get("id").asString();
        TenantContext.callAs(UUID.fromString(TENANT), "t", () -> jdbc.sql("""
                update app_user set status = 'ACTIVE', last_login_at = now() - make_interval(days => ?),
                       invited_at = now() - interval '400 days' where id = ?::uuid""")
                .params(daysSinceLogin, id).update());
        return id;
    }

    private String assign(String userId, String roleId) throws Exception {
        return post("/v1/user-roles", "{\"user_id\":\"" + userId + "\",\"role_id\":\"" + roleId + "\"}", 201, "admin")
                .get("id").asString();
    }

    private List<String> activeAssignments(String userId) throws Exception {
        return TenantContext.callAs(UUID.fromString(TENANT), "t", () -> jdbc.sql("""
                select role_id::text from user_role where user_id = ?::uuid and (valid_to is null or valid_to > now())""")
                .param(userId).query(String.class).list());
    }

    private static String decisions(String itemId, String decision, String comment) {
        return "{\"decisions\":[{\"item_id\":\"" + itemId + "\",\"decision\":\"" + decision + "\""
                + (comment == null ? "" : ",\"comment\":\"" + comment + "\"") + "}]}";
    }

    private static String item(JsonNode items, String userRoleId) {
        return find(items, "user_role_id", userRoleId).get("id").asString();
    }

    private static JsonNode find(JsonNode array, String field, String value) {
        List<JsonNode> out = new ArrayList<>();
        array.forEach(n -> {
            if (value.equals(n.path(field).asString(null))) {
                out.add(n);
            }
        });
        assertThat(out).as(field + "=" + value).hasSize(1);
        return out.getFirst();
    }

    private ObjectNode decision(JsonNode created, String approver) {
        ObjectNode row = (ObjectNode) created.deepCopy();
        row.put("status", "APPROVED");
        row.put("updated_by", approver);
        return row;
    }

    private JsonNode post(String path, String body, int expected, String user) throws Exception {
        HttpResponse<String> r = send("POST", path, body == null ? "{}" : body, user, TENANT);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = send("GET", path, null, "viewer", TENANT);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> send(String method, String path, String body, String user, String tenant)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", tenant).header("X-User", user).header("Content-Type", "application/json");
        if ("POST".equals(method)) {
            b.header("Idempotency-Key", UUID.randomUUID().toString()).POST(HttpRequest.BodyPublishers.ofString(body));
        } else {
            b.GET();
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
