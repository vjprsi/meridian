package io.meridian.workflow.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.events.OutboxWriter;
import io.meridian.workflow.domain.Caller;
import io.meridian.workflow.domain.StateMachine;
import io.meridian.workflow.infrastructure.WorkflowStore;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Human work queue: claim / release / assign / complete / escalate with SLA tracking. Completing a task that
 * belongs to a workflow instance advances the instance with the task outcome, in the same transaction.
 */
@Service
public class TaskService {
    static final String TABLE = "work_task";
    private static final List<String> PRIORITY_ORDER = List.of("LOW", "NORMAL", "HIGH", "URGENT");

    private final WorkflowStore store;
    private final InstanceService instances;
    private final OutboxWriter outbox;
    private final JsonMapper json;

    public TaskService(WorkflowStore store, InstanceService instances, OutboxWriter outbox, JsonMapper json) {
        this.store = store;
        this.instances = instances;
        this.outbox = outbox;
        this.json = json;
    }

    /**
     * Creates a stand-alone human task (not owned by a workflow instance). It starts OPEN in its queue, or CLAIMED
     * when an assignee is given.
     */
    @Transactional
    public ObjectNode create(ObjectNode values) {
        if (values.path("task_type").asString("").isBlank() || values.path("title").asString("").isBlank()
                || values.path("queue").asString("").isBlank()) {
            throw ApiException.unprocessable("validation_failed", "task_type, title and queue are required");
        }
        JsonNode roles = values.get("candidate_roles");
        if (roles != null && !roles.isNull()) {
            if (!roles.isArray()) {
                throw ApiException.unprocessable("invalid_candidate_roles", "candidate_roles must be an array of role names");
            }
            for (JsonNode r : roles) {
                if (!r.isValueNode() || r.asString().isBlank()) {
                    throw ApiException.unprocessable("invalid_candidate_roles", "candidate_roles must be non-blank strings");
                }
            }
        }
        String assignee = values.path("assignee").asString(null);
        if (assignee != null && (assignee.isBlank() || assignee.length() > 64)) {
            throw ApiException.unprocessable("invalid_assignee", "assignee must be 1-64 characters");
        }
        UUID id = Ids.newId();
        values.put("id", id.toString());
        values.put("status", assignee == null ? "OPEN" : "CLAIMED");
        if (!values.hasNonNull("priority")) {
            values.put("priority", "NORMAL");
        }
        ObjectNode row = store.insert(TABLE, values);
        outbox.publish("platform.work_task.created", TABLE, id, row);
        return row;
    }

    @Transactional
    public ObjectNode claim(UUID id, Caller caller) {
        ObjectNode t = store.lock(TABLE, id);
        String status = t.path("status").asString();
        if (("CLAIMED".equals(status) || "IN_PROGRESS".equals(status))) {
            if (caller.principal().equals(t.path("assignee").asString())) {
                return t;
            }
            throw ApiException.conflict("task_already_claimed", "Task is already claimed by " + t.path("assignee").asString());
        }
        StateMachine.WORK_TASK.require(status, "CLAIMED");
        Set<String> candidates = candidateRoles(t);
        if (!candidates.isEmpty() && caller.roles().stream().noneMatch(candidates::contains)) {
            throw new ApiException(403, "not_candidate", "Only users with one of " + candidates + " can claim this task");
        }
        ObjectNode v = json.createObjectNode();
        v.put("status", "CLAIMED");
        v.put("assignee", caller.principal());
        return store.update(TABLE, id, v);
    }

    @Transactional
    public ObjectNode release(UUID id, Caller caller) {
        ObjectNode t = store.lock(TABLE, id);
        StateMachine.WORK_TASK.require(t.path("status").asString(), "OPEN");
        if (!caller.principal().equals(t.path("assignee").asString())) {
            throw new ApiException(403, "not_task_assignee", "Only the assignee can release this task");
        }
        ObjectNode v = json.createObjectNode();
        v.put("status", "OPEN");
        v.putNull("assignee");
        return store.update(TABLE, id, v);
    }

    @Transactional
    public ObjectNode assign(UUID id, String assignee) {
        if (assignee == null || assignee.isBlank() || assignee.length() > 64) {
            throw ApiException.unprocessable("invalid_assignee", "assignee must be 1-64 characters");
        }
        ObjectNode t = store.lock(TABLE, id);
        StateMachine.WORK_TASK.require(t.path("status").asString(), "CLAIMED");
        ObjectNode v = json.createObjectNode();
        v.put("status", "CLAIMED");
        v.put("assignee", assignee);
        return store.update(TABLE, id, v);
    }

    @Transactional
    public ObjectNode complete(UUID id, String outcome, JsonNode formData, Caller caller) {
        if (outcome == null || outcome.isBlank()) {
            throw ApiException.unprocessable("outcome_required", "outcome is required");
        }
        ObjectNode t = store.lock(TABLE, id);
        String status = t.path("status").asString();
        if ("OPEN".equals(status) || "ESCALATED".equals(status)) {
            throw ApiException.conflict("task_not_claimed", "Claim the task before completing it");
        }
        StateMachine.WORK_TASK.require(status, "COMPLETED");
        if (!caller.principal().equals(t.path("assignee").asString())) {
            throw new ApiException(403, "not_task_assignee", "Only the assignee " + t.path("assignee").asString() + " can complete this task");
        }
        ObjectNode v = json.createObjectNode();
        v.put("status", "COMPLETED");
        v.put("outcome", outcome);
        v.put("completed_at", now().toString());
        if (formData != null && !formData.isNull()) {
            v.set("form_data", formData);
        }
        ObjectNode row = store.update(TABLE, id, v);
        outbox.publish("platform.work_task.completed", TABLE, id, row);
        JsonNode instanceId = row.get("workflow_instance_id");
        if (instanceId != null && !instanceId.isNull()) {
            instances.taskCompleted(UUID.fromString(instanceId.asString()), outcome, formData);
        }
        return store.get(TABLE, id);
    }

    @Transactional
    public ObjectNode escalate(UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.unprocessable("reason_required", "reason is required");
        }
        ObjectNode t = store.lock(TABLE, id);
        StateMachine.WORK_TASK.require(t.path("status").asString(), "ESCALATED");
        String priority = t.path("priority").asString("NORMAL");
        int idx = Math.min(PRIORITY_ORDER.indexOf(priority) + 1, PRIORITY_ORDER.size() - 1);
        ObjectNode v = json.createObjectNode();
        v.put("status", "ESCALATED");
        v.put("priority", PRIORITY_ORDER.get(Math.max(idx, 0)));
        v.putNull("assignee");
        v.put("escalation_reason", reason.length() > 255 ? reason.substring(0, 255) : reason);
        ObjectNode row = store.update(TABLE, id, v);
        outbox.publish("platform.work_task.escalated", TABLE, id, row);
        return row;
    }

    /**
     * PATCH guard: closed tasks are immutable and the assignee changes only through the actions (status, outcome
     * and workflow_instance_id are read-only in the contract).
     */
    @Transactional
    public void checkUpdate(UUID id, ObjectNode changes) {
        ObjectNode t = store.lock(TABLE, id);
        if (StateMachine.WORK_TASK.isTerminal(t.path("status").asString())) {
            throw ApiException.conflict("task_closed", "Task is " + t.path("status").asString() + " and can no longer change");
        }
        if (changes.has("assignee") && !changes.get("assignee").equals(t.get("assignee"))) {
            throw ApiException.unprocessable("field_requires_action",
                    "assignee can only be changed through the task actions (claim, assign, release, escalate)");
        }
    }

    /** Flags a task whose SLA passed (called by the sweeper). */
    @Transactional
    public Optional<ObjectNode> breachSla(UUID id) {
        ObjectNode t = store.lock(TABLE, id);
        JsonNode due = t.get("due_at");
        if (t.path("sla_breached").asBoolean() || due == null || due.isNull()
                || StateMachine.WORK_TASK.isTerminal(t.path("status").asString())
                || OffsetDateTime.parse(due.asString()).isAfter(now())) {
            return Optional.empty();
        }
        ObjectNode v = json.createObjectNode();
        v.put("sla_breached", true);
        ObjectNode row = store.update(TABLE, id, v);
        outbox.publish("platform.work_task.sla_breached", TABLE, id, row);
        return Optional.of(row);
    }

    private static Set<String> candidateRoles(JsonNode t) {
        Set<String> roles = new HashSet<>();
        t.path("candidate_roles").forEach(r -> roles.add(r.asString()));
        return roles;
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
