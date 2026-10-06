package io.meridian.workflow.api;

import io.meridian.workflow.api.generated.TasksApi;
import io.meridian.workflow.api.generated.model.TasksAssignRequest;
import io.meridian.workflow.api.generated.model.TasksCompleteRequest;
import io.meridian.workflow.api.generated.model.TasksEscalateRequest;
import io.meridian.workflow.api.generated.model.WorkTask;
import io.meridian.workflow.api.generated.model.WorkTaskCreate;
import io.meridian.workflow.api.generated.model.WorkTaskUpdate;
import io.meridian.workflow.application.TaskService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Human tasks: direct create, claim, release, assign, complete (advances the owning workflow), escalate; guarded PATCH. */
@RestController
public class TasksController implements TasksApi {
    private final TaskService tasks;
    private final CallerResolver callers;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public TasksController(TaskService tasks, CallerResolver callers, CrudEngine crud, Idempotency idempotency,
                           JsonMapper json) {
        this.tasks = tasks;
        this.callers = callers;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<WorkTask> tasksCreate(String idempotencyKey, WorkTaskCreate body) {
        callers.current();
        return idempotency.execute(idempotencyKey, body, WorkTask.class, () -> ResponseEntity.status(HttpStatus.CREATED)
                .body(model(tasks.create(crud.toColumns("work_task", body)))));
    }

    @Override
    public ResponseEntity<WorkTask> tasksUpdate(UUID id, String ifMatch, WorkTaskUpdate body) {
        callers.current();
        tasks.checkUpdate(id, crud.toColumns("work_task", body));
        return crud.update("work_task", WorkTask.class, body, ifMatch, Crud.params().with("id", id));
    }

    @Override
    public ResponseEntity<WorkTask> tasksClaim(UUID id, String idempotencyKey) {
        var caller = callers.current();
        return idempotency.execute(idempotencyKey, Actions.fingerprint("claim", id, null), WorkTask.class,
                () -> ResponseEntity.ok(model(tasks.claim(id, caller))));
    }

    @Override
    public ResponseEntity<WorkTask> tasksRelease(UUID id, String idempotencyKey) {
        var caller = callers.current();
        return idempotency.execute(idempotencyKey, Actions.fingerprint("release", id, null), WorkTask.class,
                () -> ResponseEntity.ok(model(tasks.release(id, caller))));
    }

    @Override
    public ResponseEntity<WorkTask> tasksAssign(UUID id, String idempotencyKey, TasksAssignRequest body) {
        callers.current();
        return idempotency.execute(idempotencyKey, Actions.fingerprint("assign", id, body), WorkTask.class,
                () -> ResponseEntity.ok(model(tasks.assign(id, body.getAssignee()))));
    }

    @Override
    public ResponseEntity<WorkTask> tasksComplete(UUID id, String idempotencyKey, TasksCompleteRequest body) {
        var caller = callers.current();
        JsonNode formData = body.getFormData() == null ? null : json.valueToTree(body.getFormData());
        return idempotency.execute(idempotencyKey, Actions.fingerprint("complete", id, body), WorkTask.class,
                () -> ResponseEntity.ok(model(tasks.complete(id, body.getOutcome(), formData, caller))));
    }

    @Override
    public ResponseEntity<WorkTask> tasksEscalate(UUID id, String idempotencyKey, TasksEscalateRequest body) {
        callers.current();
        return idempotency.execute(idempotencyKey, Actions.fingerprint("escalate", id, body), WorkTask.class,
                () -> ResponseEntity.ok(model(tasks.escalate(id, body.getReason()))));
    }

    private WorkTask model(JsonNode row) {
        return crud.read(row.toString(), WorkTask.class);
    }
}
