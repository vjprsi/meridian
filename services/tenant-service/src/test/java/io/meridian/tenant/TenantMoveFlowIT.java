package io.meridian.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.meridian.approvals.ApprovalDecision.Outcome;
import io.meridian.approvals.ApprovalEventsDispatcher;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
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
 * S20 cell registry, DR standby and the maker-checker tenant move (workflow-service stubbed, decisions dispatched as
 * they come back from it): plan -> four-eyes approval -> freeze (READ_ONLY in the routing view) -> copy hand-off ->
 * confirm -> switch -> unfreeze, plus rejection, cancellation and the cell serving states the router acts on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true",
                "meridian.tenant.move.freeze-grace=0s"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TenantMoveFlowIT {
    static final String OPERATOR = "0199b000-0000-7000-8000-0000000000e1";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApprovalEventsDispatcher dispatcher;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    final List<ObjectNode> approvalRequests = new ArrayList<>();
    String cellA;
    String cellB;
    String standby;
    String tenant;

    @BeforeEach
    void stubWorkflow() {
        when(serviceClient.post(eq("workflow-service"), eq("/v1/approval-requests"), any(), anyString()))
                .thenAnswer(inv -> {
                    ObjectNode body = json.valueToTree(inv.getArgument(2));
                    body.put("id", UUID.randomUUID().toString());
                    body.put("status", "PENDING");
                    body.put("maker", TenantContext.principal());
                    approvalRequests.add(body);
                    return body;
                });
    }

    @BeforeAll
    void cells() throws Exception {
        cellA = post("/v1/platform/cells", """
                {"code":"aws-euw1-m01","region":"eu-west-1","country":"IE","cloud_provider":"AWS","status":"ACTIVE",
                 "capacity_tenants":5,"gateway_url":"https://a.cells.example"}""", 201, "ops-1").get("id").asString();
        cellB = post("/v1/platform/cells", """
                {"code":"aws-euw1-m02","region":"eu-west-1","country":"IE","cloud_provider":"AWS","status":"ACTIVE",
                 "capacity_tenants":5,"gateway_url":"https://b.cells.example"}""", 201, "ops-1").get("id").asString();
        standby = post("/v1/platform/cells", """
                {"code":"aws-euw2-m01","region":"eu-west-2","country":"IE","cloud_provider":"AWS","status":"READ_ONLY",
                 "capacity_tenants":5,"gateway_url":"https://s.cells.example"}""", 201, "ops-1").get("id").asString();
        tenant = post("/v1/platform/tenants", """
                {"code":"movers","name":"Movers","legal_name":"Movers Ltd","tenant_type":"BANK","home_region":"eu-west-1",
                 "data_residency_country":"IE","default_currency":"EUR","default_country":"IE",
                 "primary_contact_email":"ops@movers.example","cell_id":"%s"}""".formatted(cellA), 201, "ops-1")
                .get("id").asString();
        post("/v1/platform/tenants/" + tenant + "/activate", null, 200, "ops-1");
    }

    @Test
    @Order(1)
    void cellRegistryDeploymentModelsAndServingStates() throws Exception {
        JsonNode dedicated = post("/v1/platform/cells", """
                {"code":"aws-euw1-d01","region":"eu-west-1","cloud_provider":"AWS","deployment_model":"DEDICATED",
                 "gateway_url":"https://bigbank.cells.example"}""", 201, "ops-1");
        assertThat(dedicated.get("is_dedicated").asBoolean()).isTrue();
        assertThat(dedicated.get("deployment_model").asString()).isEqualTo("DEDICATED");
        assertThat(post("/v1/platform/cells", """
                {"code":"bad-url","region":"eu-west-1","cloud_provider":"AWS","gateway_url":"ftp://x"}""", 422, "ops-1")
                .get("code").asString()).isEqualTo("invalid_url");

        // serving states change through audited actions only
        JsonNode a = get("/v1/platform/cells/" + cellA);
        HttpResponse<String> patch = send("PATCH", "/v1/platform/cells/" + cellA, "{\"status\":\"OFFLINE\"}", null, "ops-1",
                a.get("row_version").asString());
        assertThat(patch.statusCode()).isEqualTo(409);
        assertThat(json.readTree(patch.body()).get("code").asString()).isEqualTo("status_change_requires_action");

        JsonNode offline = post("/v1/platform/cells/" + cellA + "/offline", "{\"reason\":\"game day: region down\"}", 200, "ops-1");
        assertThat(offline.get("status").asString()).isEqualTo("OFFLINE");
        assertThat(offline.get("status_reason").asString()).isEqualTo("game day: region down");
        assertThat(offline.hasNonNull("status_changed_at")).isTrue();
        assertThat(post("/v1/platform/cells/" + cellA + "/offline", "{\"reason\":\"again\"}", 409, "ops-1")
                .get("code").asString()).isEqualTo("invalid_transition");
        assertThat(post("/v1/platform/cells/" + cellB + "/online", "{\"reason\":\"x\"}", 409, "ops-1")
                .get("code").asString()).isEqualTo("invalid_transition");
        assertThat(post("/v1/platform/cells/" + cellA + "/online", "{\"reason\":\"failback\"}", 200, "ops-1")
                .get("status").asString()).isEqualTo("ACTIVE");
        assertThat(outbox("platform.cell.status_changed", cellA)).isEqualTo(2);
    }

    @Test
    @Order(2)
    void standbyCellAndRoutingView() throws Exception {
        assertThat(post("/v1/platform/tenants/" + tenant + "/set-standby",
                "{\"cell_id\":\"" + cellA + "\",\"reason\":\"dr\"}", 422, "ops-1").get("code").asString())
                .isEqualTo("standby_is_home_cell");
        JsonNode t = post("/v1/platform/tenants/" + tenant + "/set-standby",
                "{\"cell_id\":\"" + standby + "\",\"reason\":\"DR pairing eu-west-2\"}", 200, "ops-1");
        assertThat(t.get("standby_cell_id").asString()).isEqualTo(standby);
        assertThat(outbox("platform.tenant.standby_changed", tenant)).isEqualTo(1);

        JsonNode routing = get("/v1/platform/tenants/" + tenant + "/routing");
        assertThat(routing.get("traffic_mode").asString()).isEqualTo("NORMAL");
        assertThat(routing.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(routing.get("primary").get("id").asString()).isEqualTo(cellA);
        assertThat(routing.get("primary").get("gateway_url").asString()).isEqualTo("https://a.cells.example");
        assertThat(routing.get("standby").get("id").asString()).isEqualTo(standby);
        assertThat(routing.get("standby").get("status").asString()).isEqualTo("READ_ONLY");

        JsonNode placements = get("/v1/platform/tenants/" + tenant + "/placements").get("data");
        assertThat(roles(placements)).containsExactlyInAnyOrder("PRIMARY:ACTIVE", "STANDBY:ACTIVE");
    }

    @Test
    @Order(3)
    void moveIsFourEyesThenFreezeCopySwitchUnfreeze() throws Exception {
        assertThat(post("/v1/platform/tenant-moves", moveBody(cellA), 422, "maker-1").get("code").asString())
                .isEqualTo("same_cell");
        JsonNode move = post("/v1/platform/tenant-moves", moveBody(cellB), 201, "maker-1");
        String id = move.get("id").asString();
        assertThat(move.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(move.get("from_cell_id").asString()).isEqualTo(cellA);
        assertThat(move.get("approval_request_id").asString()).isEqualTo(last().get("id").asString());
        assertThat(last().get("resource_type").asString()).isEqualTo("tenant_move");
        assertThat(get("/v1/platform/tenants/" + tenant).get("active_move_id").asString()).isEqualTo(id);
        assertThat(post("/v1/platform/tenant-moves", moveBody(cellB), 409, "maker-2").get("code").asString())
                .isEqualTo("move_in_progress");
        assertThat(post("/v1/platform/tenant-moves/" + id + "/freeze", null, 409, "ops-1").get("code").asString())
                .isEqualTo("invalid_transition");
        // nothing changes on a live tenant without a second operator
        assertThat(post("/v1/platform/tenants/" + tenant + "/migrate", "{\"cell_id\":\"" + cellB + "\"}", 409, "maker-1")
                .get("code").asString()).isEqualTo("use_tenant_move");

        // the maker approving their own move is refused by the four-eyes rule
        dispatcher.dispatch(UUID.fromString(OPERATOR), decision(last(), "maker-1"), Outcome.APPROVED);
        assertThat(get("/v1/platform/tenant-moves/" + id).get("status").asString()).isEqualTo("PENDING_APPROVAL");
        dispatcher.dispatch(UUID.fromString(OPERATOR), decision(last(), "checker-1"), Outcome.APPROVED);
        JsonNode approved = get("/v1/platform/tenant-moves/" + id);
        assertThat(approved.get("status").asString()).isEqualTo("APPROVED");
        assertThat(approved.get("approved_by").asString()).isEqualTo("checker-1");

        JsonNode frozen = post("/v1/platform/tenant-moves/" + id + "/freeze", null, 200, "ops-1");
        assertThat(frozen.get("status").asString()).isEqualTo("FROZEN");
        JsonNode routing = get("/v1/platform/tenants/" + tenant + "/routing");
        assertThat(routing.get("traffic_mode").asString()).isEqualTo("READ_ONLY");
        assertThat(routing.get("primary").get("id").asString()).isEqualTo(cellA);   // reads still from the source
        assertThat(outbox("platform.tenant.traffic_mode_changed", tenant)).isEqualTo(1);

        post("/v1/platform/tenant-moves/" + id + "/confirm-copy", "{\"checkpoint\":\"0/1\",\"verified\":true}", 409, "mover");
        assertThat(post("/v1/platform/tenant-moves/" + id + "/start-copy", null, 200, "ops-1").get("status").asString())
                .isEqualTo("COPYING");
        JsonNode handoff = json.readTree(jdbc.sql("select payload::text from outbox where event_type = ? and aggregate_id = ?::uuid")
                .params("platform.tenant_move.copy_requested", id).query(String.class).single());
        assertThat(handoff.get("to_cell").get("gateway_url").asString()).isEqualTo("https://b.cells.example");
        assertThat(handoff.get("from_cell").get("code").asString()).isEqualTo("aws-euw1-m01");
        assertThat(post("/v1/platform/tenant-moves/" + id + "/confirm-copy", "{\"checkpoint\":\"0/16B3748\",\"verified\":false}",
                422, "mover").get("code").asString()).isEqualTo("copy_not_verified");
        JsonNode copied = post("/v1/platform/tenant-moves/" + id + "/confirm-copy",
                "{\"checkpoint\":\"0/16B3748\",\"verified\":true}", 200, "mover");
        assertThat(copied.get("copy_checkpoint").asString()).isEqualTo("0/16B3748");

        JsonNode switched = post("/v1/platform/tenant-moves/" + id + "/switch", null, 200, "ops-1");
        assertThat(switched.get("status").asString()).isEqualTo("SWITCHED");
        routing = get("/v1/platform/tenants/" + tenant + "/routing");
        assertThat(routing.get("primary").get("id").asString()).isEqualTo(cellB);
        assertThat(routing.get("traffic_mode").asString()).isEqualTo("READ_ONLY");  // writes stay frozen until unfreeze
        assertThat(get("/v1/platform/cells/" + cellA).get("tenant_count").asInt()).isZero();
        assertThat(get("/v1/platform/cells/" + cellB).get("tenant_count").asInt()).isEqualTo(1);
        JsonNode placements = get("/v1/platform/tenants/" + tenant + "/placements").get("data");
        assertThat(roles(placements)).containsExactlyInAnyOrder("PRIMARY:COMPLETED", "PRIMARY:ACTIVE", "STANDBY:ACTIVE");
        boolean linked = false;
        for (JsonNode p : placements) {
            linked |= id.equals(p.path("move_id").asString(null)) && switched.get("placement_id").asString().equals(p.get("id").asString());
        }
        assertThat(linked).isTrue();

        JsonNode done = post("/v1/platform/tenant-moves/" + id + "/unfreeze", null, 200, "ops-1");
        assertThat(done.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(done.get("history")).hasSize(8);
        assertThat(done.get("history").get(0).get("by").asString()).isEqualTo("maker-1");
        JsonNode t = get("/v1/platform/tenants/" + tenant);
        assertThat(t.get("traffic_mode").asString()).isEqualTo("NORMAL");
        assertThat(t.has("active_move_id")).isFalse();
        assertThat(t.get("cell_id").asString()).isEqualTo(cellB);
        assertThat(post("/v1/platform/tenant-moves/" + id + "/cancel", "{\"reason\":\"late\"}", 409, "ops-1")
                .get("code").asString()).isEqualTo("invalid_transition");
        for (String e : new String[] {"requested", "approved", "frozen", "copy_requested", "copy_completed", "switched", "completed"}) {
            assertThat(outbox("platform.tenant_move." + e, id)).as(e).isEqualTo(1);
        }
        assertThat(jdbc.sql("select count(*) from outbox where event_type = 'platform.tenant.migrated' and payload->>'subject_tenant_id' = ?")
                .param(tenant).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    @Order(4)
    void rejectedAndCancelledMovesReleaseTheTenant() throws Exception {
        JsonNode rejected = post("/v1/platform/tenant-moves", moveBody(cellA), 201, "maker-1");
        dispatcher.dispatch(UUID.fromString(OPERATOR), decision(last(), "checker-1"), Outcome.REJECTED);
        assertThat(get("/v1/platform/tenant-moves/" + rejected.get("id").asString()).get("status").asString()).isEqualTo("REJECTED");
        assertThat(get("/v1/platform/tenants/" + tenant).has("active_move_id")).isFalse();

        JsonNode move = post("/v1/platform/tenant-moves", moveBody(cellA), 201, "maker-1");
        String id = move.get("id").asString();
        dispatcher.dispatch(UUID.fromString(OPERATOR), decision(last(), "checker-2"), Outcome.APPROVED);
        post("/v1/platform/tenant-moves/" + id + "/freeze", null, 200, "ops-1");
        assertThat(get("/v1/platform/tenants/" + tenant + "/routing").get("traffic_mode").asString()).isEqualTo("READ_ONLY");
        JsonNode cancelled = post("/v1/platform/tenant-moves/" + id + "/cancel", "{\"reason\":\"copy window missed\"}", 200, "ops-1");
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("cancel_reason").asString()).isEqualTo("copy window missed");
        JsonNode routing = get("/v1/platform/tenants/" + tenant + "/routing");
        assertThat(routing.get("traffic_mode").asString()).isEqualTo("NORMAL");
        assertThat(routing.get("primary").get("id").asString()).isEqualTo(cellB);
        // a late approval of the cancelled move changes nothing
        dispatcher.dispatch(UUID.fromString(OPERATOR), decision(last(), "checker-2"), Outcome.APPROVED);
        assertThat(get("/v1/platform/tenant-moves/" + id).get("status").asString()).isEqualTo("CANCELLED");
        assertThat(get("/v1/platform/tenant-moves?subject_tenant_id=" + tenant + "&limit=10").get("data")).hasSize(3);
    }

    // ------------------------------------------------------------------ helpers

    private String moveBody(String to) {
        return """
                {"subject_tenant_id":"%s","to_cell_id":"%s","reason":"rebalance eu-west-1","copy_strategy":"LOGICAL_REPLICATION"}"""
                .formatted(tenant, to);
    }

    private ObjectNode last() {
        return approvalRequests.getLast();
    }

    private ObjectNode decision(ObjectNode request, String approver) {
        ObjectNode row = request.deepCopy();
        row.put("status", "APPROVED");
        row.put("updated_by", approver);
        return row;
    }

    private static List<String> roles(JsonNode placements) {
        List<String> out = new ArrayList<>();
        placements.forEach(p -> out.add(p.get("role").asString() + ":" + p.get("status").asString()));
        return out;
    }

    private long outbox(String type, String aggregateId) {
        return jdbc.sql("select count(*) from outbox where event_type = ? and aggregate_id = ?::uuid")
                .params(type, aggregateId).query(Long.class).single();
    }

    private JsonNode post(String path, String body, int expected, String user) throws Exception {
        HttpResponse<String> r = send("POST", path, body, UUID.randomUUID().toString(), user, null);
        assertThat(r.statusCode()).as(path + " " + r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = send("GET", path, null, null, "viewer", null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String user,
                                      String ifMatch) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").header("X-User", user).header("X-Tenant-Id", OPERATOR);
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
