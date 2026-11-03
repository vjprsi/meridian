package io.meridian.agentnetwork.api;

import io.meridian.agentnetwork.api.generated.AgentsApi;
import io.meridian.agentnetwork.api.generated.model.Agent;
import io.meridian.agentnetwork.api.generated.model.AgentCreate;
import io.meridian.agentnetwork.api.generated.model.AgentFloatPage;
import io.meridian.agentnetwork.api.generated.model.AgentOutletPage;
import io.meridian.agentnetwork.api.generated.model.AgentPage;
import io.meridian.agentnetwork.api.generated.model.AgentPerformanceDailyPage;
import io.meridian.agentnetwork.api.generated.model.AgentUpdate;
import io.meridian.agentnetwork.api.generated.model.AgentsActivateRequest;
import io.meridian.agentnetwork.api.generated.model.AgentsAssignCommissionPlanRequest;
import io.meridian.agentnetwork.api.generated.model.AgentsAssignGradeRequest;
import io.meridian.agentnetwork.api.generated.model.AgentsMoveRequest;
import io.meridian.agentnetwork.api.generated.model.AgentsSuspendRequest;
import io.meridian.agentnetwork.api.generated.model.AgentsTerminateRequest;
import io.meridian.agentnetwork.application.AgentService;
import io.meridian.agentnetwork.application.PerformanceService;
import io.meridian.agentnetwork.infrastructure.Rows;
import io.meridian.common.page.Cursor;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Agents: onboarding with four-eyes activation, hierarchy, commission plans, floats, outlets and daily KPIs. */
@RestController
public class AgentsController implements AgentsApi {
    private static final Set<String> PROTECTED = Set.of("agent_level", "parent_agent_id", "currency", "customer_id");

    private final AgentService agents;
    private final PerformanceService performance;
    private final Actions actions;
    private final CrudEngine crud;
    private final Rows rows;

    public AgentsController(AgentService agents, PerformanceService performance, Actions actions, CrudEngine crud, Rows rows) {
        this.agents = agents;
        this.performance = performance;
        this.actions = actions;
        this.crud = crud;
        this.rows = rows;
    }

    @Override
    public ResponseEntity<Agent> agentsCreate(String idempotencyKey, AgentCreate body) {
        return actions.create(idempotencyKey, body, Agent.class, () -> agents.create(actions.tree(body)));
    }

    @Override
    public ResponseEntity<Agent> agentsUpdate(UUID id, String ifMatch, AgentUpdate body) {
        JsonNode req = actions.tree(body);
        if (req.hasNonNull("agent_territory_id") && rows.find("agent_territory", Http.uuid(req, "agent_territory_id")).isEmpty()) {
            throw io.meridian.common.error.ApiException.unprocessable("territory_not_found", "The territory does not exist");
        }
        return actions.guardedUpdate(crud, "agent", Agent.class, id, ifMatch, body, PROTECTED,
                "level, parent and currency are fixed; status changes through actions");
    }

    @Override
    public ResponseEntity<Agent> agentsActivate(UUID id, String idempotencyKey, AgentsActivateRequest body) {
        return actions.action(idempotencyKey, id, "activate", body, Agent.class,
                () -> agents.activate(id, Http.text(actions.tree(body), "comment")));
    }

    @Override
    public ResponseEntity<Agent> agentsSuspend(UUID id, String idempotencyKey, AgentsSuspendRequest body) {
        return actions.action(idempotencyKey, id, "suspend", body, Agent.class,
                () -> agents.suspend(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<Agent> agentsTerminate(UUID id, String idempotencyKey, AgentsTerminateRequest body) {
        JsonNode req = actions.tree(body);
        String eff = Http.text(req, "effective_date");
        return actions.action(idempotencyKey, id, "terminate", body, Agent.class,
                () -> agents.terminate(id, Http.text(req, "reason"), eff == null ? null : LocalDate.parse(eff)));
    }

    @Override
    public ResponseEntity<Agent> agentsAssignCommissionPlan(UUID id, String idempotencyKey,
                                                            AgentsAssignCommissionPlanRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "assign-commission-plan", body, Agent.class,
                () -> agents.assignCommissionPlan(id, Http.uuid(req, "commission_plan_id"),
                        LocalDate.parse(Http.text(req, "effective_from"))));
    }

    @Override
    public ResponseEntity<Agent> agentsAssignGrade(UUID id, String idempotencyKey, AgentsAssignGradeRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "assign-grade", body, Agent.class,
                () -> agents.assignGrade(id, Http.uuid(req, "grade_id"), Http.text(req, "reason")));
    }

    @Override
    public ResponseEntity<Agent> agentsMove(UUID id, String idempotencyKey, AgentsMoveRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "move", body, Agent.class,
                () -> agents.move(id, Http.uuid(req, "category_id"), Http.uuid(req, "grade_id"), Http.text(req, "reason")));
    }

    @Override
    public ResponseEntity<AgentPage> agentsSubAgents(UUID id, Integer limit, String cursor) {
        crud.get("agent", Agent.class, Crud.params().with("id", id));
        return crud.list("agent", AgentPage.class, Crud.params().with("parent_agent_id", id).with("limit", limit)
                .with("cursor", cursor));
    }

    @Override
    public ResponseEntity<Map<String, Object>> agentsHierarchy(UUID id) {
        TenantContext.require();
        return actions.object(agents.hierarchy(id));
    }

    @Override
    public ResponseEntity<AgentOutletPage> agentsOutlets(UUID id, Integer limit, String cursor) {
        crud.get("agent", Agent.class, Crud.params().with("id", id));
        return crud.list("agent_outlet", AgentOutletPage.class, Crud.params().with("agent_id", id).with("limit", limit)
                .with("cursor", cursor));
    }

    @Override
    public ResponseEntity<AgentFloatPage> agentsFloat(UUID id, Integer limit, String cursor) {
        crud.get("agent", Agent.class, Crud.params().with("id", id));
        return crud.list("agent_float", AgentFloatPage.class, Crud.params().with("agent_id", id).with("limit", limit)
                .with("cursor", cursor));
    }

    @Override
    public ResponseEntity<AgentPerformanceDailyPage> agentsPerformance(UUID id, LocalDate from, LocalDate to, Integer limit,
                                                                       String cursor) {
        crud.get("agent", Agent.class, Crud.params().with("id", id));
        int n = Cursor.limit(limit);
        UUID after = cursor == null || cursor.isBlank() ? null : Cursor.decode(cursor);
        List<ObjectNode> list = rows.query("""
                select row_to_json(p)::text from agent_performance_daily p
                where p.agent_id = ? and (?::date is null or p.business_date >= ?::date)
                  and (?::date is null or p.business_date <= ?::date) and (?::uuid is null or p.id < ?::uuid)
                order by p.id desc limit ?""", id, str(from), str(from), str(to), str(to), after, after, n + 1);
        boolean more = list.size() > n;
        ObjectNode page = rows.object();
        ArrayNode data = page.putArray("data");
        (more ? list.subList(0, n) : list).forEach(data::add);
        page.put("has_more", more);
        if (more) {
            page.put("next_cursor", Cursor.encode(Rows.uuid(list.get(n - 1), "id")));
        } else {
            page.putNull("next_cursor");
        }
        return ResponseEntity.ok(actions.model(page, AgentPerformanceDailyPage.class));
    }

    private static String str(LocalDate d) {
        return d == null ? null : d.toString();
    }

    @Override
    public ResponseEntity<Map<String, Object>> agentsEodPerformance(LocalDate businessDate, String idempotencyKey) {
        return actions.objectAction(idempotencyKey, "eod-performance", Map.of("business_date", businessDate.toString()),
                () -> performance.aggregate(businessDate));
    }
}
