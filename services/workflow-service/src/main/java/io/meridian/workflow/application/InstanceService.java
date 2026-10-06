package io.meridian.workflow.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.events.OutboxWriter;
import io.meridian.workflow.domain.StateMachine;
import io.meridian.workflow.domain.WorkflowGraph;
import io.meridian.workflow.infrastructure.WorkflowStore;
import io.meridian.web.client.ServiceClient;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A small, durable state-machine engine for workflow instances. Each call advances the instance synchronously
 * until it waits (human task or external signal), completes, or fails; state lives in the instance row, so the
 * engine is stateless and safe to run on every replica. SERVICE steps call other Meridian services with an
 * idempotency key per step visit, so a retry after a failure never repeats a call that already succeeded.
 */
@Service
public class InstanceService {
    static final String TABLE = "workflow_instance";
    private static final int MAX_STEPS_PER_RUN = 100;
    private static final Pattern PATH_VAR = Pattern.compile("\\{([A-Za-z0-9_]+)}");

    private final WorkflowStore store;
    private final OutboxWriter outbox;
    private final ServiceClient services;
    private final JsonMapper json;

    public InstanceService(WorkflowStore store, OutboxWriter outbox, ServiceClient services, JsonMapper json) {
        this.store = store;
        this.outbox = outbox;
        this.services = services;
        this.json = json;
    }

    @Transactional
    public ObjectNode start(ObjectNode values) {
        UUID defId = UUID.fromString(values.get("workflow_definition_id").asString());
        ObjectNode def = store.find(DefinitionService.TABLE, defId)
                .orElseThrow(() -> ApiException.unprocessable("unknown_definition", "Workflow definition " + defId + " does not exist"));
        if (!"PUBLISHED".equals(def.path("status").asString())) {
            throw ApiException.unprocessable("definition_not_published",
                    "Workflow definition " + def.path("code").asString() + " v" + def.path("version").asInt()
                            + " is " + def.path("status").asString());
        }
        WorkflowGraph graph = WorkflowGraph.parse(def.get("definition"));
        ObjectNode vars = values.path("variables").isObject() ? (ObjectNode) values.get("variables") : json.createObjectNode();
        JsonNode required = def.path("input_schema").path("required");
        if (required.isArray()) {
            StringBuilder missing = new StringBuilder();
            required.forEach(r -> {
                if (!vars.hasNonNull(r.asString())) {
                    missing.append(missing.isEmpty() ? "" : ", ").append(r.asString());
                }
            });
            if (!missing.isEmpty()) {
                throw ApiException.unprocessable("missing_input", "Required workflow input missing: " + missing);
            }
        }
        UUID id = Ids.newId();
        values.put("id", id.toString());
        values.put("status", "RUNNING");
        values.put("current_step", graph.start());
        values.put("engine_run_id", "meridian-local:" + id);
        values.put("started_at", now().toString());
        values.set("variables", vars);
        values.set("engine_state", json.createObjectNode());
        ObjectNode inst = store.insert(TABLE, values);
        return run(inst, graph, graph.start());
    }

    @Transactional
    public ObjectNode signal(UUID id, String signal, JsonNode data) {
        if (signal == null || signal.isBlank()) {
            throw ApiException.unprocessable("signal_required", "signal is required");
        }
        ObjectNode inst = store.lock(TABLE, id);
        String status = inst.path("status").asString();
        if (!"WAITING".equals(status)) {
            throw ApiException.conflict("invalid_transition", "Workflow instance is " + status + "; only WAITING instances accept signals");
        }
        WorkflowGraph graph = graphOf(inst);
        WorkflowGraph.Step step = graph.step(inst.path("current_step").asString());
        String target = step.target(signal);
        if (target == null) {
            throw ApiException.unprocessable("unknown_signal",
                    "Step " + step.id() + " does not accept signal '" + signal + "'; expected one of " + step.transitions().keySet());
        }
        if ("TASK".equals(step.type())) {
            cancelOpenTasks(id, "Superseded by signal " + signal);
        }
        ObjectNode vars = vars(inst);
        if (data != null && data.isObject()) {
            data.propertyNames().forEach(k -> vars.set(k, data.get(k)));
        }
        StateMachine.WORKFLOW_INSTANCE.require(status, "RUNNING");
        return run(inst, graph, target);
    }

    /** Continues the instance after its current human task completed with the given outcome. */
    @Transactional
    public void taskCompleted(UUID instanceId, String outcome, JsonNode formData) {
        ObjectNode inst = store.lock(TABLE, instanceId);
        if (!"WAITING".equals(inst.path("status").asString())) {
            return; // the instance moved on (signal, cancel); the task outcome is recorded on the task only
        }
        WorkflowGraph graph = graphOf(inst);
        WorkflowGraph.Step step = graph.step(inst.path("current_step").asString());
        if (!"TASK".equals(step.type())) {
            return;
        }
        String target = step.target(outcome);
        if (target == null) {
            throw ApiException.unprocessable("invalid_outcome",
                    "Outcome '" + outcome + "' is not valid for step " + step.id() + "; expected one of " + step.transitions().keySet());
        }
        ObjectNode result = vars(inst).putObject(step.id());
        result.put("outcome", outcome);
        if (formData != null && !formData.isNull()) {
            result.set("form_data", formData);
        }
        run(inst, graph, target);
    }

    @Transactional
    public ObjectNode cancel(UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.unprocessable("reason_required", "reason is required");
        }
        ObjectNode inst = store.lock(TABLE, id);
        StateMachine.WORKFLOW_INSTANCE.require(inst.path("status").asString(), "CANCELLED");
        cancelOpenTasks(id, "Workflow cancelled: " + reason);
        ObjectNode values = json.createObjectNode();
        values.put("status", "CANCELLED");
        values.put("completed_at", now().toString());
        values.put("error", "Cancelled: " + reason);
        ObjectNode row = store.update(TABLE, id, values);
        outbox.publish("platform.workflow_instance.cancelled", TABLE, id, row);
        return row;
    }

    @Transactional
    public ObjectNode retry(UUID id) {
        ObjectNode inst = store.lock(TABLE, id);
        String status = inst.path("status").asString();
        if (!"FAILED".equals(status)) {
            throw ApiException.conflict("invalid_transition", "Only FAILED workflow instances can be retried; this one is " + status);
        }
        StateMachine.WORKFLOW_INSTANCE.require(status, "RUNNING");
        inst.putNull("error");
        return run(inst, graphOf(inst), inst.path("current_step").asString());
    }

    // ------------------------------------------------------------------ engine

    private ObjectNode run(ObjectNode inst, WorkflowGraph graph, String stepId) {
        UUID id = UUID.fromString(inst.get("id").asString());
        ObjectNode vars = vars(inst);
        ObjectNode engine = engineState(inst);
        ObjectNode values = json.createObjectNode();
        values.put("status", "RUNNING");
        values.putNull("error");
        values.set("variables", vars);
        values.set("engine_state", engine);
        String current = stepId;
        for (int i = 0; ; i++) {
            if (i >= MAX_STEPS_PER_RUN) {
                return fail(id, values, current, "Step limit exceeded; the definition probably loops without waiting");
            }
            WorkflowGraph.Step step = graph.step(current);
            values.put("current_step", current);
            switch (step.type()) {
                case "END" -> {
                    values.put("outcome", step.config().path("outcome").asString("COMPLETED"));
                    values.put("status", "COMPLETED");
                    values.put("completed_at", now().toString());
                    ObjectNode row = store.update(TABLE, id, values);
                    outbox.publish("platform.workflow_instance.completed", TABLE, id, row);
                    return row;
                }
                case "TASK" -> {
                    createTask(inst, step);
                    values.put("status", "WAITING");
                    return store.update(TABLE, id, values);
                }
                case "WAIT" -> {
                    values.put("status", "WAITING");
                    return store.update(TABLE, id, values);
                }
                case "DECISION" -> {
                    String variable = step.config().path("variable").asString("");
                    String value = vars.path(variable).asString("");
                    String next = step.target(value);
                    if (next == null) {
                        return fail(id, values, current,
                                "Decision " + step.id() + " has no transition for " + variable + "='" + value + "'");
                    }
                    current = next;
                }
                case "SERVICE" -> {
                    try {
                        JsonNode response = call(id, step, vars, engine);
                        vars.set(step.id(), response);
                    } catch (ApiException e) {
                        return fail(id, values, current, "Step " + step.id() + " failed: " + e.code() + ": " + e.getMessage());
                    } catch (RuntimeException e) {
                        return fail(id, values, current, "Step " + step.id() + " failed: " + e);
                    }
                    current = step.target("next");
                }
                default -> throw new IllegalStateException("unsupported step type " + step.type());
            }
        }
    }

    private ObjectNode fail(UUID id, ObjectNode values, String step, String error) {
        values.put("status", "FAILED");
        values.put("current_step", step);
        values.put("error", error.length() > 4000 ? error.substring(0, 4000) : error);
        ObjectNode row = store.update(TABLE, id, values);
        outbox.publish("platform.workflow_instance.failed", TABLE, id, row);
        return row;
    }

    private JsonNode call(UUID instanceId, WorkflowGraph.Step step, ObjectNode vars, ObjectNode engine) {
        JsonNode call = step.config().get("call");
        String service = call.get("service").asString();
        String method = call.path("method").asString("POST");
        String path = expand(call.get("path").asString(), vars);
        ObjectNode visits = engine.path("visits").isObject() ? (ObjectNode) engine.get("visits") : engine.putObject("visits");
        // A retry repeats the same visit (same key) so the downstream de-duplicates a call that actually succeeded.
        int visit = visits.path(step.id()).asInt(0);
        if (!engine.path("failed_step").asString("").equals(step.id())) {
            visit++;
            visits.put(step.id(), visit);
        }
        engine.put("failed_step", step.id());
        String key = ("wf:" + instanceId + ":" + step.id() + ":" + visit);
        JsonNode body = call.has("body") ? call.get("body") : vars;
        JsonNode out = switch (method) {
            case "GET" -> services.get(service, path);
            case "PATCH" -> services.patch(service, path, body, call.path("if_match").asString(null));
            default -> services.post(service, path, body, key.length() > 128 ? key.substring(0, 128) : key);
        };
        engine.remove("failed_step");
        return out == null ? json.createObjectNode() : out;
    }

    private void createTask(ObjectNode inst, WorkflowGraph.Step step) {
        JsonNode cfg = step.config().get("task");
        ObjectNode t = json.createObjectNode();
        UUID taskId = Ids.newId();
        t.put("id", taskId.toString());
        t.put("workflow_instance_id", inst.get("id").asString());
        t.put("task_type", cfg.get("task_type").asString());
        t.put("title", cfg.path("title").asString(step.id()));
        if (cfg.hasNonNull("description")) {
            t.put("description", cfg.get("description").asString());
        }
        if (inst.hasNonNull("subject_type")) {
            t.put("subject_type", inst.get("subject_type").asString());
        }
        if (inst.hasNonNull("subject_id")) {
            t.put("subject_id", inst.get("subject_id").asString());
        }
        t.put("queue", cfg.get("queue").asString());
        if (cfg.path("candidate_roles").isArray()) {
            t.set("candidate_roles", cfg.get("candidate_roles"));
        }
        t.put("priority", cfg.path("priority").asString("NORMAL"));
        t.put("status", "OPEN");
        if (cfg.has("sla_hours")) {
            long minutes = Math.round(cfg.get("sla_hours").asDouble() * 60);
            t.put("due_at", now().plus(Duration.ofMinutes(minutes)).toString());
        }
        ObjectNode row = store.insert(TaskService.TABLE, t);
        outbox.publish("platform.work_task.created", TaskService.TABLE, taskId, row);
    }

    private void cancelOpenTasks(UUID instanceId, String note) {
        for (ObjectNode task : store.openTasksOfInstance(instanceId)) {
            ObjectNode v = json.createObjectNode();
            v.put("status", "CANCELLED");
            v.put("outcome", "CANCELLED");
            v.put("completed_at", now().toString());
            String desc = task.path("description").asString("");
            v.put("description", (desc.isEmpty() ? "" : desc + "\n") + note);
            store.update(TaskService.TABLE, UUID.fromString(task.get("id").asString()), v);
        }
    }

    private WorkflowGraph graphOf(ObjectNode inst) {
        ObjectNode def = store.get(DefinitionService.TABLE, UUID.fromString(inst.get("workflow_definition_id").asString()));
        return WorkflowGraph.parse(def.get("definition"));
    }

    private ObjectNode vars(ObjectNode inst) {
        if (!inst.path("variables").isObject()) {
            inst.set("variables", json.createObjectNode());
        }
        return (ObjectNode) inst.get("variables");
    }

    /** Engine bookkeeping (step visits, failed step), kept in the non-API {@code engine_state} column. */
    private ObjectNode engineState(ObjectNode inst) {
        if (!inst.path("engine_state").isObject()) {
            inst.set("engine_state", json.createObjectNode());
        }
        return (ObjectNode) inst.get("engine_state");
    }

    private static String expand(String path, JsonNode vars) {
        Matcher m = PATH_VAR.matcher(path);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            JsonNode v = vars.get(m.group(1));
            if (v == null || v.isNull() || v.isContainer()) {
                throw ApiException.unprocessable("missing_variable", "Path variable " + m.group(1) + " is not set");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(URLEncoder.encode(v.asString(), StandardCharsets.UTF_8)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
