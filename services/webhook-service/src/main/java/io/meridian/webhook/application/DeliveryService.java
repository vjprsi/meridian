package io.meridian.webhook.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.webhook.domain.EventTypePattern;
import io.meridian.webhook.domain.PayloadFilter;
import io.meridian.webhook.domain.RetrySchedule;
import io.meridian.webhook.domain.WebhookRules;
import io.meridian.webhook.domain.WebhookSignature;
import io.meridian.webhook.infrastructure.WebhookHttpSender;
import io.meridian.webhook.infrastructure.WebhookStore;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Event ingestion, signed HTTP dispatch with at-least-once retries, ordering per aggregate, endpoint health
 * (auto-disable), manual retry and test sends. HTTP calls happen outside database transactions; a short lease on
 * {@code next_attempt_at} keeps concurrent dispatchers from sending the same delivery twice.
 */
@Service
public class DeliveryService {
    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);
    static final String TEST_EVENT_PREFIX = "evt_test_";
    private static final int LEASE_SECONDS = 120;

    enum Mode { SCHEDULED, MANUAL_RETRY, TEST }

    private final WebhookStore store;
    private final EndpointService endpoints;
    private final WebhookHttpSender sender;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final int disableThreshold;
    private final int batchSize;

    public DeliveryService(WebhookStore store, EndpointService endpoints, WebhookHttpSender sender, OutboxWriter outbox,
                           TransactionTemplate tx, JsonMapper json,
                           @Value("${meridian.webhooks.disable-after-failures:20}") int disableThreshold,
                           @Value("${meridian.webhooks.dispatch-batch-size:100}") int batchSize) {
        this.store = store;
        this.endpoints = endpoints;
        this.sender = sender;
        this.outbox = outbox;
        this.tx = tx;
        this.json = json;
        this.disableThreshold = disableThreshold;
        this.batchSize = batchSize;
    }

    // ------------------------------------------------------------------ ingestion

    /**
     * Fans an event out to every endpoint with a matching active subscription (exact type, prefix wildcard or
     * {@code *}, plus optional payload filter). Idempotent per (endpoint, event id): redelivered Kafka records
     * do not create duplicate deliveries. Webhook-service's own events are never fanned out (no feedback loops).
     */
    @Transactional
    public List<UUID> ingest(String eventId, String eventType, String orderingKey, JsonNode payload) {
        if (eventId == null || eventId.isBlank() || !EventTypePattern.isConcreteType(eventType)) {
            throw ApiException.unprocessable("invalid_event", "Event id and a concrete event type are required");
        }
        if (eventType.startsWith("platform.webhook_")) {
            return List.of();
        }
        Set<UUID> targets = new LinkedHashSet<>();
        for (WebhookStore.SubscriptionRow s : store.activeSubscriptions()) {
            if (!targets.contains(s.endpointId()) && EventTypePattern.matches(s.eventType(), eventType)
                    && passes(s.filterExpression(), payload)) {
                targets.add(s.endpointId());
            }
        }
        List<UUID> created = new ArrayList<>();
        String payloadJson = json.writeValueAsString(payload == null ? json.createObjectNode() : payload);
        String key = orderingKey == null ? null : orderingKey.substring(0, Math.min(64, orderingKey.length()));
        for (UUID endpointId : targets) {
            store.insertDeliveryIfAbsent(TenantContext.require(), TenantContext.principal(), Ids.newId(), endpointId,
                    truncate(eventId, 64), eventType, key, payloadJson, null).ifPresent(created::add);
        }
        return created;
    }

    private boolean passes(String filter, JsonNode payload) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        try {
            return PayloadFilter.parse(filter).test(path -> {
                JsonNode n = payload;
                for (String seg : path) {
                    n = n == null ? null : n.get(seg);
                }
                if (n == null || n.isNull() || n.isMissingNode()) {
                    return null;
                }
                return n.isValueNode() ? n.asString() : n.toString();
            });
        } catch (ApiException e) {
            log.warn("Ignoring subscription with invalid filter '{}': {}", filter, e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------ test + manual retry

    /** Creates a synthetic event for the endpoint and dispatches it once, synchronously (no automatic retries). */
    public String test(UUID endpointId, String eventType) {
        if (!EventTypePattern.isConcreteType(eventType)) {
            throw ApiException.unprocessable("invalid_event_type", "event_type must be a concrete event type");
        }
        UUID deliveryId = tx.execute(s -> {
            WebhookStore.EndpointRow e = store.requireEndpoint(endpointId, false);
            WebhookRules.requireDeliverable(e.status());
            ObjectNode payload = json.createObjectNode();
            payload.put("test", true);
            payload.put("event_type", eventType);
            payload.put("message", "Test event from Meridian webhooks");
            UUID id = Ids.newId();
            store.insertDeliveryIfAbsent(TenantContext.require(), TenantContext.principal(), id, endpointId,
                    TEST_EVENT_PREFIX + id, eventType, null, json.writeValueAsString(payload), null);
            return id;
        });
        return dispatch(deliveryId, Mode.TEST);
    }

    public String retry(UUID deliveryId) {
        return dispatch(deliveryId, Mode.MANUAL_RETRY);
    }

    // ------------------------------------------------------------------ scheduled dispatch

    /** Dispatches due deliveries of the current tenant, honouring per-aggregate ordering and per-endpoint rate caps. */
    public int processDue() {
        List<UUID> due = store.dueDeliveries(batchSize);
        Map<UUID, Integer> perEndpoint = new HashMap<>();
        Map<UUID, Integer> caps = new HashMap<>();
        int sent = 0;
        for (UUID id : due) {
            Optional<WebhookStore.DeliveryRow> d = store.delivery(id, false);
            if (d.isEmpty()) {
                continue;
            }
            UUID ep = d.get().endpointId();
            int cap = caps.computeIfAbsent(ep, k -> store.endpoint(k, false).map(WebhookStore.EndpointRow::maxRps).orElse(0));
            if (perEndpoint.merge(ep, 1, Integer::sum) > cap) {
                continue;
            }
            try {
                dispatch(id, Mode.SCHEDULED);
                sent++;
            } catch (RuntimeException e) {
                log.warn("Dispatch of webhook delivery {} failed: {}", id, e.toString());
            }
        }
        return sent;
    }

    // ------------------------------------------------------------------ dispatch core

    private record Prepared(UUID deliveryId, UUID endpointId, String url, Map<String, String> headers, String body,
                            Mode mode, String priorStatus) {}

    String dispatch(UUID deliveryId, Mode mode) {
        Prepared p = tx.execute(s -> prepare(deliveryId, mode));
        if (p != null) {
            WebhookHttpSender.Result r = sender.post(p.url(), p.headers(), p.body());
            tx.executeWithoutResult(s -> complete(p, r));
        }
        return store.deliveryJson(deliveryId);
    }

    private Prepared prepare(UUID deliveryId, Mode mode) {
        WebhookStore.DeliveryRow d = store.requireDelivery(deliveryId, true);
        WebhookStore.EndpointRow e = store.requireEndpoint(d.endpointId(), false);
        switch (mode) {
            case SCHEDULED -> {
                if ("DISABLED".equals(e.status()) || !store.claimDue(deliveryId, LEASE_SECONDS)) {
                    return null;
                }
            }
            case MANUAL_RETRY -> {
                WebhookRules.requireRetryable(d.status());
                WebhookRules.requireDeliverable(e.status());
                store.lease(deliveryId, "RETRYING", LEASE_SECONDS);
            }
            case TEST -> store.lease(deliveryId, "PENDING", LEASE_SECONDS);
        }
        long t = Instant.now().getEpochSecond();
        ObjectNode envelope = json.createObjectNode();
        envelope.put("id", d.eventId());
        envelope.put("type", d.eventType());
        envelope.put("api_version", e.apiVersion());
        envelope.put("created_at", d.createdAt().withOffsetSameInstant(ZoneOffset.UTC).toString());
        envelope.put("delivery_id", d.id().toString());
        envelope.set("data", json.readTree(d.payload()));
        String body = json.writeValueAsString(envelope);
        Map<String, String> headers = new LinkedHashMap<>();
        if (e.customHeaders() != null) {
            json.readTree(e.customHeaders()).properties().forEach(en -> headers.put(en.getKey(), en.getValue().asString()));
        }
        headers.put("Content-Type", "application/json");
        headers.put("User-Agent", "Meridian-Webhooks/1.0");
        headers.put("Meridian-Event-Id", d.eventId());
        headers.put("Meridian-Event-Type", d.eventType());
        headers.put("Meridian-Delivery-Id", d.id().toString());
        headers.put("Meridian-Attempt", Integer.toString(d.attemptCount() + 1));
        headers.put(WebhookSignature.HEADER, WebhookSignature.header(endpoints.secrets(e).active(Instant.now()), t, body));
        return new Prepared(d.id(), e.id(), e.url(), headers, body, mode, d.status());
    }

    private void complete(Prepared p, WebhookHttpSender.Result r) {
        WebhookStore.EndpointRow e = store.requireEndpoint(p.endpointId(), true);
        WebhookStore.DeliveryRow d = store.requireDelivery(p.deliveryId(), true);
        int attemptNo = d.attemptCount() + 1;
        Map<String, String> logged = new LinkedHashMap<>(p.headers());
        logged.computeIfPresent(WebhookSignature.HEADER, (k, v) -> v.replaceAll("v1=[0-9a-f]+", "v1=<redacted>"));
        String error = r.error() != null ? r.error()
                : WebhookRules.isSuccess(r.statusCode()) ? null : "HTTP " + r.statusCode();
        store.insertAttempt(TenantContext.require(), TenantContext.principal(), d.id(), attemptNo,
                json.writeValueAsString(logged), r.statusCode(), r.responseBody(), r.latencyMs(), error);

        if (error == null) {
            store.completeAttempt(d.id(), attemptNo, "SUCCEEDED", null, r.statusCode(), null, true);
            store.recordEndpointSuccess(e.id());
            return;
        }
        String status;
        OffsetDateTime next = null;
        if (p.mode() == Mode.TEST) {
            status = "FAILED";
        } else {
            Optional<OffsetDateTime> n = RetrySchedule.nextAttempt(attemptNo, d.createdAt(), OffsetDateTime.now());
            if (n.isPresent()) {
                status = "RETRYING";
                next = n.get();
            } else {
                // a manual retry of an already abandoned/failed delivery does not re-announce abandonment
                status = "ABANDONED".equals(p.priorStatus()) || "FAILED".equals(p.priorStatus()) ? "FAILED" : "ABANDONED";
            }
        }
        store.completeAttempt(d.id(), attemptNo, status, next, r.statusCode(), truncate(error, 500), false);
        if ("ABANDONED".equals(status)) {
            outbox.publish("platform.webhook_delivery.abandoned", "webhook_delivery", d.id(),
                    json.readTree(store.deliveryJson(d.id())));
        }
        int failures = e.consecutiveFailures() + 1;
        String newStatus = WebhookRules.afterFailure(e.status(), failures, disableThreshold);
        boolean nowDisabled = "DISABLED".equals(newStatus) && !"DISABLED".equals(e.status());
        store.recordEndpointFailure(e.id(), failures, newStatus,
                nowDisabled ? "Disabled automatically after " + failures + " consecutive failed deliveries" : null);
        if (nowDisabled) {
            outbox.publish("platform.webhook_endpoint.disabled", "webhook_endpoint", e.id(),
                    json.readTree(store.endpointEventJson(e.id())));
        }
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
