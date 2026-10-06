package io.meridian.crm.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.crm.api.generated.WorkQueuesApi;
import io.meridian.crm.api.generated.model.WorkItemPage;
import io.meridian.crm.api.generated.model.WorkQueue;
import io.meridian.crm.api.generated.model.WorkQueueCreate;
import io.meridian.crm.api.generated.model.WorkQueueUpdate;
import io.meridian.crm.application.WorkService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Work queues: configuration, items and live statistics. */
@RestController
public class WorkQueuesController implements WorkQueuesApi {
    private final WorkService work;
    private final CrudEngine crud;
    private final Http http;

    public WorkQueuesController(WorkService work, CrudEngine crud, Http http) {
        this.work = work;
        this.crud = crud;
        this.http = http;
    }

    @Override
    public ResponseEntity<WorkQueue> workQueuesCreate(String idempotencyKey, WorkQueueCreate body) {
        return http.created(idempotencyKey, "work-queues.create", body, WorkQueue.class, () -> work.createQueue(body));
    }

    @Override
    public ResponseEntity<WorkQueue> workQueuesUpdate(UUID id, String ifMatch, WorkQueueUpdate body) {
        TenantContext.require();
        return http.withEtag(work.updateQueue(id, ifMatch, body), WorkQueue.class);
    }

    @Override
    public ResponseEntity<WorkItemPage> workQueuesItems(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        crud.findOne("work_queue", Map.of("id", id)).orElseThrow(() -> ApiException.notFound("work queue", id));
        return crud.list("work_item", WorkItemPage.class,
                Crud.params().with("work_queue_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<Map<String, Object>> workQueuesStats(UUID id) {
        TenantContext.require();
        return http.query(work.stats(id));
    }
}
