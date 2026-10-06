package io.meridian.workflow.api;

import io.meridian.workflow.api.generated.WorkflowDefinitionsApi;
import io.meridian.workflow.api.generated.model.ApprovalRequest;
import io.meridian.workflow.api.generated.model.WorkflowDefinition;
import io.meridian.workflow.api.generated.model.WorkflowDefinitionCreate;
import io.meridian.workflow.api.generated.model.WorkflowDefinitionUpdate;
import io.meridian.workflow.application.DefinitionService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Versioned workflow definitions: create (next version per code), edit drafts, publish via approval, deprecate. */
@RestController
public class WorkflowDefinitionsController implements WorkflowDefinitionsApi {
    private final DefinitionService definitions;
    private final CallerResolver callers;
    private final CrudEngine crud;
    private final Idempotency idempotency;

    public WorkflowDefinitionsController(DefinitionService definitions, CallerResolver callers, CrudEngine crud,
                                         Idempotency idempotency) {
        this.definitions = definitions;
        this.callers = callers;
        this.crud = crud;
        this.idempotency = idempotency;
    }

    @Override
    public ResponseEntity<WorkflowDefinition> workflowDefinitionsCreate(String idempotencyKey, WorkflowDefinitionCreate body) {
        callers.current();
        return idempotency.execute(idempotencyKey, body, WorkflowDefinition.class, () -> ResponseEntity
                .status(HttpStatus.CREATED)
                .body(crud.read(definitions.create(crud.toColumns("workflow_definition", body)).toString(),
                        WorkflowDefinition.class)));
    }

    @Override
    public ResponseEntity<WorkflowDefinition> workflowDefinitionsUpdate(UUID id, String ifMatch, WorkflowDefinitionUpdate body) {
        callers.current();
        definitions.checkUpdate(id, crud.toColumns("workflow_definition", body));
        return crud.update("workflow_definition", WorkflowDefinition.class, body, ifMatch, Crud.params().with("id", id));
    }

    @Override
    public ResponseEntity<ApprovalRequest> workflowDefinitionsPublish(UUID id, String idempotencyKey) {
        var caller = callers.current();
        return idempotency.execute(idempotencyKey, Actions.fingerprint("publish-definition", id, null), ApprovalRequest.class,
                () -> ResponseEntity.status(HttpStatus.OK)
                        .body(crud.read(definitions.publish(id, caller).toString(), ApprovalRequest.class)));
    }

    @Override
    public ResponseEntity<WorkflowDefinition> workflowDefinitionsDeprecate(UUID id, String idempotencyKey) {
        callers.current();
        return idempotency.execute(idempotencyKey, Actions.fingerprint("deprecate-definition", id, null), WorkflowDefinition.class,
                () -> ResponseEntity.ok(crud.read(definitions.deprecate(id).toString(), WorkflowDefinition.class)));
    }
}
