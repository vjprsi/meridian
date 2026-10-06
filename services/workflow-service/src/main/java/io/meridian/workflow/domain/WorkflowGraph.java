package io.meridian.workflow.domain;

import io.meridian.common.error.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * A workflow definition as a simple state machine, parsed from the definition JSON:
 * <pre>
 * {"start":"review",
 *  "steps":[
 *    {"id":"review","type":"TASK","task":{"task_type":"KYC_REVIEW","title":"Review","queue":"kyc",
 *       "candidate_roles":["KYC_OFFICER"],"sla_hours":4,"priority":"HIGH"},
 *     "transitions":{"APPROVED":"book","REJECTED":"declined"}},
 *    {"id":"book","type":"SERVICE","call":{"service":"deposit-service","method":"POST","path":"/v1/..."},
 *     "transitions":{"next":"risk"}},
 *    {"id":"risk","type":"DECISION","variable":"risk_level","transitions":{"HIGH":"review","default":"done"}},
 *    {"id":"wait_docs","type":"WAIT","transitions":{"documents_received":"review"}},
 *    {"id":"done","type":"END"},
 *    {"id":"declined","type":"END","outcome":"DECLINED"}]}
 * </pre>
 * TASK steps wait for a human task (its outcome is the signal), WAIT steps for an external signal, SERVICE steps
 * call another Meridian service and continue on {@code next}, DECISION steps branch on a variable, END completes.
 */
public final class WorkflowGraph {
    public static final Set<String> STEP_TYPES = Set.of("TASK", "WAIT", "SERVICE", "DECISION", "END");
    private static final Set<String> PRIORITIES = Set.of("LOW", "NORMAL", "HIGH", "URGENT");
    private static final int MAX_STEPS = 200;

    public record Step(String id, String type, Map<String, String> transitions, JsonNode config) {
        public boolean waits() {
            return "TASK".equals(type) || "WAIT".equals(type);
        }

        /** Target step for a signal / outcome, falling back to the {@code default} transition. */
        public String target(String signal) {
            String t = transitions.get(signal);
            return t != null ? t : transitions.get("default");
        }
    }

    private final String start;
    private final Map<String, Step> steps;

    private WorkflowGraph(String start, Map<String, Step> steps) {
        this.start = start;
        this.steps = steps;
    }

    public String start() {
        return start;
    }

    public Step step(String id) {
        Step s = steps.get(id);
        if (s == null) {
            throw ApiException.unprocessable("unknown_step", "Step " + id + " is not part of the definition");
        }
        return s;
    }

    public static WorkflowGraph parse(JsonNode def) {
        if (def == null || !def.isObject()) {
            throw invalid("definition must be an object with start and steps");
        }
        JsonNode arr = def.get("steps");
        if (arr == null || !arr.isArray() || arr.isEmpty()) {
            throw invalid("definition.steps must be a non-empty array");
        }
        if (arr.size() > MAX_STEPS) {
            throw invalid("definition has more than " + MAX_STEPS + " steps");
        }
        Map<String, Step> steps = new LinkedHashMap<>();
        for (JsonNode n : arr) {
            String id = n.path("id").asString("");
            if (!id.matches("[A-Za-z0-9_.-]{1,128}")) {
                throw invalid("step id '" + id + "' must be 1-128 chars of letters, digits, _ . -");
            }
            String type = n.path("type").asString("");
            if (!STEP_TYPES.contains(type)) {
                throw invalid("step " + id + " has unknown type '" + type + "'; allowed " + STEP_TYPES);
            }
            Map<String, String> transitions = new LinkedHashMap<>();
            JsonNode t = n.get("transitions");
            if (t != null && !t.isNull()) {
                if (!t.isObject()) {
                    throw invalid("step " + id + " transitions must be an object of signal -> step");
                }
                t.propertyNames().forEach(k -> transitions.put(k, t.get(k).asString()));
            }
            if (steps.put(id, new Step(id, type, transitions, n)) != null) {
                throw invalid("duplicate step id " + id);
            }
        }
        String start = def.path("start").asString(steps.keySet().iterator().next());
        WorkflowGraph g = new WorkflowGraph(start, steps);
        g.validate();
        return g;
    }

    private void validate() {
        if (!steps.containsKey(start)) {
            throw invalid("start step '" + start + "' does not exist");
        }
        boolean hasEnd = false;
        for (Step s : steps.values()) {
            for (Map.Entry<String, String> e : s.transitions().entrySet()) {
                if (!steps.containsKey(e.getValue())) {
                    throw invalid("step " + s.id() + " transition '" + e.getKey() + "' targets unknown step " + e.getValue());
                }
            }
            switch (s.type()) {
                case "END" -> {
                    hasEnd = true;
                    if (!s.transitions().isEmpty()) {
                        throw invalid("END step " + s.id() + " must not have transitions");
                    }
                }
                case "TASK" -> {
                    JsonNode task = s.config().get("task");
                    if (task == null || !task.isObject() || task.path("task_type").asString("").isBlank()
                            || task.path("queue").asString("").isBlank()) {
                        throw invalid("TASK step " + s.id() + " needs task.task_type and task.queue");
                    }
                    String p = task.path("priority").asString("NORMAL");
                    if (!PRIORITIES.contains(p)) {
                        throw invalid("TASK step " + s.id() + " has invalid priority " + p);
                    }
                    if (task.has("sla_hours") && task.get("sla_hours").asDouble() <= 0) {
                        throw invalid("TASK step " + s.id() + " sla_hours must be positive");
                    }
                    requireTransitions(s);
                }
                case "WAIT", "DECISION" -> requireTransitions(s);
                case "SERVICE" -> {
                    JsonNode call = s.config().get("call");
                    if (call == null || call.path("service").asString("").isBlank()
                            || !call.path("path").asString("").startsWith("/")) {
                        throw invalid("SERVICE step " + s.id() + " needs call.service and call.path");
                    }
                    String m = call.path("method").asString("POST");
                    if (!List.of("GET", "POST", "PATCH").contains(m)) {
                        throw invalid("SERVICE step " + s.id() + " call.method must be GET, POST or PATCH");
                    }
                    if (s.target("next") == null) {
                        throw invalid("SERVICE step " + s.id() + " needs a 'next' transition");
                    }
                }
                default -> throw invalid("unsupported step type " + s.type());
            }
        }
        if (!hasEnd) {
            throw invalid("definition needs at least one END step");
        }
    }

    private static void requireTransitions(Step s) {
        if (s.transitions().isEmpty()) {
            throw invalid(s.type() + " step " + s.id() + " needs at least one transition");
        }
    }

    private static ApiException invalid(String msg) {
        return ApiException.unprocessable("invalid_definition", msg);
    }
}
