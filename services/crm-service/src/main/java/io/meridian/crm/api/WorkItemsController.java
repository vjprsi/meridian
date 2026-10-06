package io.meridian.crm.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.crm.api.generated.WorkItemsApi;
import io.meridian.crm.api.generated.model.WorkItem;
import io.meridian.crm.api.generated.model.WorkItemCreate;
import io.meridian.crm.api.generated.model.WorkItemUpdate;
import io.meridian.crm.api.generated.model.WorkItemsAssignRequest;
import io.meridian.crm.api.generated.model.WorkItemsCancelRequest;
import io.meridian.crm.api.generated.model.WorkItemsNextRequest;
import io.meridian.crm.api.generated.model.WorkItemsReleaseRequest;
import io.meridian.crm.application.Json;
import io.meridian.crm.application.WorkService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Work items: pull routing, assignment, start, release, completion and cancellation. */
@RestController
public class WorkItemsController implements WorkItemsApi {
    private final WorkService work;
    private final Http http;

    public WorkItemsController(WorkService work, Http http) {
        this.work = work;
        this.http = http;
    }

    @Override
    public ResponseEntity<WorkItem> workItemsCreate(String idempotencyKey, WorkItemCreate body) {
        return http.created(idempotencyKey, "work-items.create", body, WorkItem.class, () -> work.createItem(body));
    }

    @Override
    public ResponseEntity<WorkItem> workItemsUpdate(UUID id, String ifMatch, WorkItemUpdate body) {
        TenantContext.require();
        return http.withEtag(work.updateItem(id, ifMatch, body), WorkItem.class);
    }

    @Override
    public ResponseEntity<WorkItem> workItemsNext(String idempotencyKey, WorkItemsNextRequest body) {
        JsonNode req = http.tree(body);
        return http.ok(idempotencyKey, "work-items.next", null, body, WorkItem.class,
                () -> work.next(Json.uuid(req, "work_queue_id"), Json.uuid(req, "assignee_id"), req.get("skills")));
    }

    @Override
    public ResponseEntity<WorkItem> workItemsAssign(UUID id, String idempotencyKey, WorkItemsAssignRequest body) {
        UUID assignee = Json.uuid(http.tree(body), "assigned_to");
        return http.ok(idempotencyKey, "work-items.assign", id, body, WorkItem.class, () -> work.assign(id, assignee));
    }

    @Override
    public ResponseEntity<WorkItem> workItemsStart(UUID id, String idempotencyKey) {
        return http.ok(idempotencyKey, "work-items.start", id, null, WorkItem.class, () -> work.start(id));
    }

    @Override
    public ResponseEntity<WorkItem> workItemsRelease(UUID id, String idempotencyKey, WorkItemsReleaseRequest body) {
        String reason = Json.text(http.tree(body), "reason");
        return http.ok(idempotencyKey, "work-items.release", id, body, WorkItem.class, () -> work.release(id, reason));
    }

    @Override
    public ResponseEntity<WorkItem> workItemsComplete(UUID id, String idempotencyKey) {
        return http.ok(idempotencyKey, "work-items.complete", id, null, WorkItem.class, () -> work.complete(id));
    }

    @Override
    public ResponseEntity<WorkItem> workItemsCancel(UUID id, String idempotencyKey, WorkItemsCancelRequest body) {
        String reason = Json.text(http.tree(body), "reason");
        return http.ok(idempotencyKey, "work-items.cancel", id, body, WorkItem.class, () -> work.cancel(id, reason));
    }
}
