package io.meridian.businesschannel.api;

import io.meridian.businesschannel.api.generated.BusinessWebhooksApi;
import io.meridian.businesschannel.api.generated.model.BusinessWebhooksAddSubscriptionsRequest;
import io.meridian.businesschannel.api.generated.model.BusinessWebhooksCreateRequest;
import io.meridian.businesschannel.api.generated.model.BusinessWebhooksDisableRequest;
import io.meridian.businesschannel.api.generated.model.WebhookDeliveryPage;
import io.meridian.businesschannel.api.generated.model.WebhookEndpoint;
import io.meridian.businesschannel.api.generated.model.WebhookEndpointPage;
import io.meridian.businesschannel.api.generated.model.WebhookSubscription;
import io.meridian.businesschannel.api.generated.model.WebhookSubscriptionPage;
import io.meridian.businesschannel.application.IntegrationService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Webhook endpoints of the business (ADMIN); subscriptions only deliver the business's own events. */
@RestController
public class BusinessWebhooksController implements BusinessWebhooksApi {
    private final IntegrationService integrations;
    private final Http http;

    public BusinessWebhooksController(IntegrationService integrations, Http http) {
        this.integrations = integrations;
        this.http = http;
    }

    @Override
    public ResponseEntity<WebhookEndpointPage> businessWebhooksList(Integer limit, String cursor) {
        return http.ok(integrations.endpoints(), WebhookEndpointPage.class);
    }

    @Override
    public ResponseEntity<WebhookEndpoint> businessWebhooksCreate(String idempotencyKey,
                                                                  BusinessWebhooksCreateRequest body) {
        return http.ok(integrations.createEndpoint(http.body(body), idempotencyKey), WebhookEndpoint.class);
    }

    @Override
    public ResponseEntity<WebhookEndpoint> businessWebhooksGet(UUID endpointId) {
        return http.ok(integrations.endpoint(endpointId), WebhookEndpoint.class);
    }

    @Override
    public ResponseEntity<Void> businessWebhooksDelete(UUID endpointId, String idempotencyKey) {
        integrations.deleteEndpoint(endpointId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<WebhookEndpoint> businessWebhooksDisable(UUID endpointId, String idempotencyKey,
                                                                   BusinessWebhooksDisableRequest body) {
        return http.ok(integrations.endpointState(endpointId, "disable", http.body(body), idempotencyKey),
                WebhookEndpoint.class);
    }

    @Override
    public ResponseEntity<WebhookEndpoint> businessWebhooksEnable(UUID endpointId, String idempotencyKey) {
        return http.ok(integrations.endpointState(endpointId, "enable", http.body(null), idempotencyKey),
                WebhookEndpoint.class);
    }

    @Override
    public ResponseEntity<WebhookSubscriptionPage> businessWebhooksSubscriptions(UUID endpointId, Integer limit,
                                                                                 String cursor) {
        return http.ok(integrations.subscriptions(endpointId, limit, cursor), WebhookSubscriptionPage.class);
    }

    @Override
    public ResponseEntity<WebhookSubscription> businessWebhooksAddSubscriptions(UUID endpointId, String idempotencyKey,
            BusinessWebhooksAddSubscriptionsRequest body) {
        return http.ok(integrations.subscribe(endpointId, http.body(body), idempotencyKey), WebhookSubscription.class);
    }

    @Override
    public ResponseEntity<WebhookDeliveryPage> businessWebhooksDeliveries(UUID endpointId, Integer limit, String cursor) {
        return http.ok(integrations.deliveries(endpointId, limit, cursor), WebhookDeliveryPage.class);
    }
}
