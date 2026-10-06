package io.meridian.workflow.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.workflow.domain.Caller;
import io.meridian.workflow.domain.StateMachine;
import io.meridian.workflow.domain.WorkflowGraph;
import io.meridian.workflow.infrastructure.WorkflowStore;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Workflow definitions are versioned per code: a new definition with an existing code becomes the next version.
 * Only DRAFT versions can be edited. Publishing goes through maker-checker approval; on approval the version is
 * PUBLISHED and earlier published versions of the code are deprecated (running instances keep their version).
 */
@Service
public class DefinitionService implements ApprovalExecutor {
    static final String TABLE = "workflow_definition";
    public static final String RESOURCE = "workflow_definition";

    private final WorkflowStore store;
    private final ApprovalService approvals;
    private final JsonMapper json;

    public DefinitionService(WorkflowStore store, @org.springframework.context.annotation.Lazy ApprovalService approvals,
                             JsonMapper json) {
        this.store = store;
        this.approvals = approvals;
        this.json = json;
    }

    @Transactional
    public ObjectNode create(ObjectNode values) {
        if (values.has("status") && !"DRAFT".equals(values.get("status").asString())) {
            throw ApiException.unprocessable("status_change_requires_action",
                    "New definitions start as DRAFT; use /publish to publish them");
        }
        WorkflowGraph.parse(values.get("definition"));
        validateInputSchema(values.get("input_schema"));
        values.put("id", Ids.newId().toString());
        values.put("status", "DRAFT");
        values.put("version", store.nextDefinitionVersion(values.get("code").asString()));
        if (!values.hasNonNull("engine")) {
            values.put("engine", "TEMPORAL");
        }
        return store.insert(TABLE, values);
    }

    /** Validates a PATCH: only DRAFT versions are editable and status changes go through actions. */
    @Transactional
    public void checkUpdate(UUID id, ObjectNode changes) {
        ObjectNode d = store.lock(TABLE, id);
        if (!"DRAFT".equals(d.path("status").asString())) {
            throw ApiException.conflict("definition_not_editable",
                    "Definition version is " + d.path("status").asString() + "; create a new version instead");
        }
        if (changes.has("status") && !"DRAFT".equals(changes.get("status").asString())) {
            throw ApiException.unprocessable("status_change_requires_action", "Use /publish or /deprecate to change status");
        }
        if (changes.has("code") && !changes.get("code").asString().equals(d.path("code").asString())) {
            throw ApiException.unprocessable("code_immutable", "The code of a definition cannot change");
        }
        if (changes.has("definition")) {
            WorkflowGraph.parse(changes.get("definition"));
        }
        validateInputSchema(changes.get("input_schema"));
    }

    @Transactional
    public ObjectNode publish(UUID id, Caller caller) {
        ObjectNode d = store.lock(TABLE, id);
        StateMachine.WORKFLOW_DEFINITION.require(d.path("status").asString(), "PUBLISHED");
        WorkflowGraph.parse(d.get("definition"));
        ObjectNode payload = json.createObjectNode();
        payload.put("workflow_definition_id", id.toString());
        payload.put("code", d.path("code").asString());
        payload.put("version", d.path("version").asInt());
        return approvals.request(RESOURCE, id, "publish",
                "Publish workflow " + d.path("code").asString() + " v" + d.path("version").asInt(),
                "Publish version " + d.path("version").asInt() + " of workflow definition " + d.path("name").asString(),
                payload, caller);
    }

    @Transactional
    public ObjectNode deprecate(UUID id) {
        ObjectNode d = store.lock(TABLE, id);
        StateMachine.WORKFLOW_DEFINITION.require(d.path("status").asString(), "DEPRECATED");
        ObjectNode values = json.createObjectNode();
        values.put("status", "DEPRECATED");
        return store.update(TABLE, id, values);
    }

    // ------------------------------------------------------------------ approval execution

    @Override
    public String resourceType() {
        return RESOURCE;
    }

    @Override
    public String action() {
        return "publish";
    }

    @Override
    public JsonNode execute(JsonNode approvalRequest) {
        UUID id = UUID.fromString(approvalRequest.get("resource_id").asString());
        ObjectNode d = store.lock(TABLE, id);
        StateMachine.WORKFLOW_DEFINITION.require(d.path("status").asString(), "PUBLISHED");
        List<UUID> previous = store.publishedVersionsOf(d.path("code").asString(), id);
        for (UUID prev : previous) {
            ObjectNode dep = json.createObjectNode();
            dep.put("status", "DEPRECATED");
            store.update(TABLE, prev, dep);
        }
        ObjectNode values = json.createObjectNode();
        values.put("status", "PUBLISHED");
        store.update(TABLE, id, values);
        ObjectNode result = json.createObjectNode();
        result.put("workflow_definition_id", id.toString());
        result.put("status", "PUBLISHED");
        result.set("deprecated_versions", json.valueToTree(previous.stream().map(UUID::toString).toList()));
        return result;
    }

    private static void validateInputSchema(JsonNode schema) {
        if (schema == null || schema.isNull()) {
            return;
        }
        JsonNode req = schema.get("required");
        if (req != null && !req.isArray()) {
            throw ApiException.unprocessable("invalid_input_schema", "input_schema.required must be an array of names");
        }
    }
}
