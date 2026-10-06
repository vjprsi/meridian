package io.meridian.webhook.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.webhook.api.generated.WebhookSubscriptionsApi;
import io.meridian.webhook.api.generated.model.WebhookSubscription;
import io.meridian.webhook.api.generated.model.WebhookSubscriptionCreate;
import io.meridian.webhook.api.generated.model.WebhookSubscriptionUpdate;
import io.meridian.webhook.application.EndpointService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Subscriptions: generic CRUD plus validation of event-type patterns, filters and the endpoint reference. */
@RestController
public class WebhookSubscriptionsController implements WebhookSubscriptionsApi {
    private final EndpointService endpoints;
    private final CrudEngine crud;

    public WebhookSubscriptionsController(EndpointService endpoints, CrudEngine crud) {
        this.endpoints = endpoints;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<WebhookSubscription> webhookSubscriptionsCreate(String idempotencyKey,
                                                                          WebhookSubscriptionCreate body) {
        TenantContext.require();
        endpoints.validateSubscription(body, Optional.empty());
        return crud.create("webhook_subscription", WebhookSubscription.class, body, idempotencyKey, Crud.params());
    }

    @Override
    public ResponseEntity<WebhookSubscription> webhookSubscriptionsUpdate(UUID id, String ifMatch,
                                                                          WebhookSubscriptionUpdate body) {
        TenantContext.require();
        endpoints.validateSubscription(body, Optional.of(id));
        return crud.update("webhook_subscription", WebhookSubscription.class, body, ifMatch, Crud.params().with("id", id));
    }
}
