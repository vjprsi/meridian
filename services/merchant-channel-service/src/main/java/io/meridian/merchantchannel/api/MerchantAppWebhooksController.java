package io.meridian.merchantchannel.api;

import io.meridian.merchantchannel.api.generated.MerchantAppWebhooksApi;
import io.meridian.merchantchannel.api.generated.model.MerchantAppWebhooksAddSubscriptionsRequest;
import io.meridian.merchantchannel.api.generated.model.MerchantAppWebhooksCreateRequest;
import io.meridian.merchantchannel.api.generated.model.MerchantAppWebhooksDisableRequest;
import io.meridian.merchantchannel.api.generated.model.WebhookDeliveryPage;
import io.meridian.merchantchannel.api.generated.model.WebhookEndpoint;
import io.meridian.merchantchannel.api.generated.model.WebhookEndpointPage;
import io.meridian.merchantchannel.api.generated.model.WebhookSubscription;
import io.meridian.merchantchannel.api.generated.model.WebhookSubscriptionPage;
import io.meridian.merchantchannel.application.IntegrationService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Webhook endpoints of the merchant (OWNER); subscriptions only deliver the merchant's own events. */
@RestController
public class MerchantAppWebhooksController implements MerchantAppWebhooksApi {
    private final IntegrationService integrations;
    private final Http http;

    public MerchantAppWebhooksController(IntegrationService integrations, Http http) {
        this.integrations = integrations;
        this.http = http;
    }

    @Override
    public ResponseEntity<WebhookEndpointPage> merchantAppWebhooksList(Integer limit, String cursor) {
        return http.ok(integrations.endpoints(), WebhookEndpointPage.class);
    }

    @Override
    public ResponseEntity<WebhookEndpoint> merchantAppWebhooksCreate(String idempotencyKey,
                                                                  MerchantAppWebhooksCreateRequest body) {
        return http.ok(integrations.createEndpoint(http.body(body), idempotencyKey), WebhookEndpoint.class);
    }

    @Override
    public ResponseEntity<WebhookEndpoint> merchantAppWebhooksGet(UUID endpointId) {
        return http.ok(integrations.endpoint(endpointId), WebhookEndpoint.class);
    }

    @Override
    public ResponseEntity<Void> merchantAppWebhooksDelete(UUID endpointId, String idempotencyKey) {
        integrations.deleteEndpoint(endpointId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<WebhookEndpoint> merchantAppWebhooksDisable(UUID endpointId, String idempotencyKey,
                                                                   MerchantAppWebhooksDisableRequest body) {
        return http.ok(integrations.endpointState(endpointId, "disable", http.body(body), idempotencyKey),
                WebhookEndpoint.class);
    }

    @Override
    public ResponseEntity<WebhookEndpoint> merchantAppWebhooksEnable(UUID endpointId, String idempotencyKey) {
        return http.ok(integrations.endpointState(endpointId, "enable", http.body(null), idempotencyKey),
                WebhookEndpoint.class);
    }

    @Override
    public ResponseEntity<WebhookSubscriptionPage> merchantAppWebhooksSubscriptions(UUID endpointId, Integer limit,
                                                                                 String cursor) {
        return http.ok(integrations.subscriptions(endpointId, limit, cursor), WebhookSubscriptionPage.class);
    }

    @Override
    public ResponseEntity<WebhookSubscription> merchantAppWebhooksAddSubscriptions(UUID endpointId, String idempotencyKey,
            MerchantAppWebhooksAddSubscriptionsRequest body) {
        return http.ok(integrations.subscribe(endpointId, http.body(body), idempotencyKey), WebhookSubscription.class);
    }

    @Override
    public ResponseEntity<WebhookDeliveryPage> merchantAppWebhooksDeliveries(UUID endpointId, Integer limit, String cursor) {
        return http.ok(integrations.deliveries(endpointId, limit, cursor), WebhookDeliveryPage.class);
    }
}
