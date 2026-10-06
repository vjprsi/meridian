package io.meridian.notification.application;

import io.meridian.common.error.ApiException;
import io.meridian.events.OutboxWriter;
import io.meridian.notification.domain.DeliveryPolicy;
import io.meridian.notification.infrastructure.ChannelAdapter;
import io.meridian.notification.infrastructure.NotificationStore;
import io.meridian.web.crud.CrudEngine;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Hands a queued notification to its channel's providers: providers are tried in failover order (ACTIVE before
 * TESTING, then by priority); every call is recorded as an append-only delivery attempt. Transient failures are
 * retried with exponential backoff up to {@code max-attempts} per send cycle (a resend starts a new cycle);
 * permanent rejections by every provider fail the notification immediately.
 */
@Service
public class DeliveryService {
    private final NotificationStore store;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final JsonMapper json;
    private final List<ChannelAdapter> adapters;
    private final int maxAttempts;
    private final Duration baseDelay;
    private final Duration maxDelay;

    public DeliveryService(NotificationStore store, CrudEngine crud, OutboxWriter outbox, JsonMapper json,
                           List<ChannelAdapter> adapters,
                           @Value("${meridian.notifications.retry.max-attempts:5}") int maxAttempts,
                           @Value("${meridian.notifications.retry.base-delay-seconds:30}") long baseDelaySeconds,
                           @Value("${meridian.notifications.retry.max-delay-seconds:3600}") long maxDelaySeconds) {
        this.store = store;
        this.crud = crud;
        this.outbox = outbox;
        this.json = json;
        this.adapters = adapters;
        this.maxAttempts = maxAttempts;
        this.baseDelay = Duration.ofSeconds(baseDelaySeconds);
        this.maxDelay = Duration.ofSeconds(maxDelaySeconds);
    }

    /**
     * Attempts delivery if the notification is due. Returns the notification row.
     *
     * @param forcedProviderId only use this provider (provider test), no retries
     * @param newCycle         a resend: the retry budget starts over
     */
    @Transactional
    public String dispatch(UUID id, UUID forcedProviderId, boolean newCycle, Instant now) {
        JsonNode n = json.readTree(store.lockNotification(id).orElseThrow(() -> ApiException.notFound("notification", id)));
        String status = n.get("status").asString();
        if (!"QUEUED".equals(status) && !"SCHEDULED".equals(status)) {
            return row(id);
        }
        if (n.hasNonNull("expires_at") && !OffsetDateTime.parse(n.get("expires_at").asString()).toInstant().isAfter(now)) {
            store.update(id, "EXPIRED", "failure_reason = ?", "expired before delivery");
            return row(id);
        }
        if (forcedProviderId == null && n.hasNonNull("scheduled_at")
                && OffsetDateTime.parse(n.get("scheduled_at").asString()).toInstant().isAfter(now)) {
            return row(id); // not due yet
        }
        String channel = n.get("channel").asString();
        List<ChannelAdapter.Provider> providers = new ArrayList<>();
        if (forcedProviderId != null) {
            JsonNode p = json.readTree(store.json("notification_provider", forcedProviderId)
                    .orElseThrow(() -> ApiException.notFound("notification provider", forcedProviderId)));
            providers.add(provider(p));
        } else {
            store.providersFor(channel).forEach(p -> providers.add(provider(json.readTree(p))));
        }
        if (providers.isEmpty()) {
            if ("IN_APP".equals(channel)) {
                // The in-app inbox is our own channel: the message is delivered by storing it.
                store.update(id, "DELIVERED", "sent_at = now(), delivered_at = now(), failure_reason = null");
                String row = row(id);
                outbox.publish("platform.notification.sent", "notification", id, json.readTree(row));
                outbox.publish("platform.notification.delivered", "notification", id, json.readTree(row));
                return row;
            }
            return fail(id, "no_active_provider: no ACTIVE or TESTING provider for channel " + channel);
        }

        int cycle = store.currentCycle(id) + (newCycle && store.totalAttempts(id) > 0 ? 1 : 0);
        int used = newCycle ? 0 : store.attemptCount(id, cycle);
        int attemptNo = store.totalAttempts(id);
        boolean retryable = false;
        String lastError = null;
        ChannelAdapter.Message message = new ChannelAdapter.Message(id, channel, n.get("destination").asString(),
                text(n, "subject"), n.get("body").asString());
        for (ChannelAdapter.Provider provider : providers) {
            if (used >= maxAttempts) {
                break;
            }
            long started = System.nanoTime();
            ChannelAdapter.Result result = adapter(provider).send(provider, message);
            int latency = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - started) / 1_000_000);
            used++;
            attemptNo++;
            recordAttempt(id, provider, attemptNo, cycle, result, latency);
            if (result.accepted()) {
                store.update(id, "SENT", "sent_at = now(), scheduled_at = null, failure_reason = null, provider_message_id = ?",
                        result.providerMessageId());
                String row = row(id);
                outbox.publish("platform.notification.sent", "notification", id, json.readTree(row));
                return row;
            }
            retryable |= result.retryable();
            lastError = provider.providerName() + ": " + (result.errorCode() == null ? result.status() : result.errorCode());
        }
        if (forcedProviderId == null && retryable && used < maxAttempts) {
            Instant next = now.plus(DeliveryPolicy.backoff(used, baseDelay, maxDelay));
            store.update(id, "QUEUED", "scheduled_at = ?, failure_reason = ?",
                    OffsetDateTime.ofInstant(next, ZoneOffset.UTC), truncate("retrying: " + lastError));
            return row(id);
        }
        return fail(id, (retryable ? "retries_exhausted: " : "rejected: ") + lastError);
    }

    private String fail(UUID id, String reason) {
        store.update(id, "FAILED", "failure_reason = ?", truncate(reason));
        String row = row(id);
        outbox.publish("platform.notification.failed", "notification", id, json.readTree(row));
        return row;
    }

    private void recordAttempt(UUID notificationId, ChannelAdapter.Provider provider, int attemptNo, int cycle,
                               ChannelAdapter.Result result, int latencyMs) {
        ObjectNode a = json.createObjectNode();
        a.put("notification_id", notificationId.toString());
        a.put("notification_provider_id", provider.id().toString());
        a.put("attempt_no", attemptNo);
        a.put("status", result.status());
        a.put("cycle", cycle);
        a.set("provider_response", json.valueToTree(result.response()));
        if (result.errorCode() != null) {
            a.put("error_code", truncate(result.errorCode(), 64));
        }
        a.put("latency_ms", latencyMs);
        crud.insert("notification_delivery_attempt", a);
    }

    private ChannelAdapter adapter(ChannelAdapter.Provider p) {
        return adapters.stream().filter(a -> a.supports(p)).findFirst()
                .orElseThrow(() -> new IllegalStateException("no adapter for " + p.providerName()));
    }

    private ChannelAdapter.Provider provider(JsonNode p) {
        JsonNode config = p.hasNonNull("config") ? p.get("config") : json.createObjectNode();
        return new ChannelAdapter.Provider(UUID.fromString(p.get("id").asString()), p.get("channel").asString(),
                p.get("provider_name").asString(), text(p, "sender_id"), config, p.get("status").asString());
    }

    private String row(UUID id) {
        return store.json("notification", id).orElseThrow();
    }

    private static String text(JsonNode n, String f) {
        return n.hasNonNull(f) ? n.get(f).asString() : null;
    }

    private static String truncate(String s) {
        return truncate(s, 255);
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
