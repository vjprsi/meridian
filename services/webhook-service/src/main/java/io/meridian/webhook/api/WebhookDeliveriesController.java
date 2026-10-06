package io.meridian.webhook.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import io.meridian.webhook.api.generated.WebhookDeliveriesApi;
import io.meridian.webhook.api.generated.model.WebhookDelivery;
import io.meridian.webhook.api.generated.model.WebhookDeliveryAttemptPage;
import io.meridian.webhook.application.DeliveryService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Delivery log: manual retry and the HTTP attempts of a delivery. */
@RestController
public class WebhookDeliveriesController implements WebhookDeliveriesApi {
    private final DeliveryService deliveries;
    private final CrudEngine crud;
    private final Idempotency idempotency;

    public WebhookDeliveriesController(DeliveryService deliveries, CrudEngine crud, Idempotency idempotency) {
        this.deliveries = deliveries;
        this.crud = crud;
        this.idempotency = idempotency;
    }

    @Override
    public ResponseEntity<WebhookDelivery> webhookDeliveriesRetry(UUID id, String idempotencyKey) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, Responses.op("retry", id, null), WebhookDelivery.class,
                () -> ResponseEntity.ok(crud.read(deliveries.retry(id), WebhookDelivery.class)));
    }

    @Override
    public ResponseEntity<WebhookDeliveryAttemptPage> webhookDeliveriesAttempts(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        if (crud.findOne("webhook_delivery", Map.of("id", id)).isEmpty()) {
            throw ApiException.notFound("webhook delivery", id);
        }
        return crud.list("webhook_delivery_attempt", WebhookDeliveryAttemptPage.class,
                Crud.params().with("webhook_delivery_id", id).with("limit", limit).with("cursor", cursor));
    }
}
