package io.meridian.webhook.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import io.meridian.webhook.api.generated.EventReplaysApi;
import io.meridian.webhook.api.generated.model.WebhookReplayJob;
import io.meridian.webhook.api.generated.model.WebhookReplayJobCreate;
import io.meridian.webhook.application.ReplayService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Event replay jobs: validated creation (processed asynchronously by the dispatcher) and cancellation. */
@RestController
public class EventReplaysController implements EventReplaysApi {
    private final ReplayService replays;
    private final CrudEngine crud;
    private final Idempotency idempotency;

    public EventReplaysController(ReplayService replays, CrudEngine crud, Idempotency idempotency) {
        this.replays = replays;
        this.crud = crud;
        this.idempotency = idempotency;
    }

    @Override
    public ResponseEntity<WebhookReplayJob> eventReplaysCreate(String idempotencyKey, WebhookReplayJobCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, WebhookReplayJob.class, () -> ResponseEntity
                .status(HttpStatus.CREATED).body(crud.read(replays.create(body), WebhookReplayJob.class)));
    }

    @Override
    public ResponseEntity<WebhookReplayJob> eventReplaysCancel(UUID id, String idempotencyKey) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, Responses.op("cancel-replay", id, null), WebhookReplayJob.class,
                () -> ResponseEntity.ok(crud.read(replays.cancel(id), WebhookReplayJob.class)));
    }
}
