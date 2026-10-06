package io.meridian.workflow.api;

import io.meridian.workflow.api.generated.WorkflowInstancesApi;
import io.meridian.workflow.api.generated.model.WorkflowInstance;
import io.meridian.workflow.api.generated.model.WorkflowInstanceCreate;
import io.meridian.workflow.api.generated.model.WorkflowInstancesCancelRequest;
import io.meridian.workflow.api.generated.model.WorkflowInstancesSignalRequest;
import io.meridian.workflow.application.InstanceService;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Workflow instances: start (runs until the first wait point), signal, cancel, retry from the failed step. */
@RestController
public class WorkflowInstancesController implements WorkflowInstancesApi {
    private final InstanceService instances;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public WorkflowInstancesController(InstanceService instances, CrudEngine crud, Idempotency idempotency, JsonMapper json) {
        this.instances = instances;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<WorkflowInstance> workflowInstancesCreate(String idempotencyKey, WorkflowInstanceCreate body) {
        return idempotency.execute(idempotencyKey, body, WorkflowInstance.class, () -> ResponseEntity
                .status(HttpStatus.CREATED)
                .body(model(instances.start(crud.toColumns("workflow_instance", body)))));
    }

    @Override
    public ResponseEntity<WorkflowInstance> workflowInstancesSignal(UUID id, String idempotencyKey,
                                                                    WorkflowInstancesSignalRequest body) {
        JsonNode req = json.valueToTree(body);
        return idempotency.execute(idempotencyKey, Actions.fingerprint("signal", id, body), WorkflowInstance.class,
                () -> ResponseEntity.ok(model(instances.signal(id, req.path("signal").asString(null), req.get("data")))));
    }

    @Override
    public ResponseEntity<WorkflowInstance> workflowInstancesCancel(UUID id, String idempotencyKey,
                                                                    WorkflowInstancesCancelRequest body) {
        JsonNode req = json.valueToTree(body);
        return idempotency.execute(idempotencyKey, Actions.fingerprint("cancel-instance", id, body), WorkflowInstance.class,
                () -> ResponseEntity.ok(model(instances.cancel(id, req.path("reason").asString(null)))));
    }

    @Override
    public ResponseEntity<WorkflowInstance> workflowInstancesRetry(UUID id, String idempotencyKey) {
        return idempotency.execute(idempotencyKey, Actions.fingerprint("retry-instance", id, null), WorkflowInstance.class,
                () -> ResponseEntity.ok(model(instances.retry(id))));
    }

    private WorkflowInstance model(JsonNode row) {
        return crud.read(row.toString(), WorkflowInstance.class);
    }
}
