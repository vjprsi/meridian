package io.meridian.webhook.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.web.crud.CrudEngine;
import io.meridian.webhook.domain.EventTypePattern;
import io.meridian.webhook.domain.WebhookRules;
import io.meridian.webhook.infrastructure.WebhookStore;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Event replay: re-delivers the events originally delivered to an endpoint within a time range (optionally only
 * some event types) as new deliveries tagged with the replay job, which then flow through normal dispatch.
 */
@Service
public class ReplayService {
    static final Duration MAX_RANGE = Duration.ofDays(31);

    private final WebhookStore store;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    public ReplayService(WebhookStore store, CrudEngine crud, OutboxWriter outbox, TransactionTemplate tx, JsonMapper json) {
        this.store = store;
        this.crud = crud;
        this.outbox = outbox;
        this.tx = tx;
        this.json = json;
    }

    @Transactional
    public String create(Object body) {
        ObjectNode v = crud.toColumns("webhook_replay_job", body);
        OffsetDateTime from = OffsetDateTime.parse(v.get("from_time").asString());
        OffsetDateTime to = OffsetDateTime.parse(v.get("to_time").asString());
        if (!from.isBefore(to)) {
            throw ApiException.unprocessable("invalid_time_range", "from_time must be before to_time");
        }
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw ApiException.unprocessable("replay_range_too_large", "A replay may cover at most 31 days");
        }
        if (from.isAfter(io.meridian.common.time.Clocks.now())) {
            throw ApiException.unprocessable("invalid_time_range", "from_time must be in the past");
        }
        eventTypes(v.get("event_types"));
        UUID endpointId = UUID.fromString(v.get("webhook_endpoint_id").asString());
        WebhookStore.EndpointRow e = store.endpoint(endpointId, false).orElseThrow(() ->
                ApiException.unprocessable("endpoint_not_found", "Webhook endpoint " + endpointId + " does not exist"));
        WebhookRules.requireDeliverable(e.status());
        v.put("status", "QUEUED");
        v.remove("events_total");
        v.remove("events_replayed");
        return crud.insert("webhook_replay_job", v);
    }

    @Transactional
    public String cancel(UUID id) {
        WebhookStore.ReplayRow job = store.replay(id, true).orElseThrow(() -> ApiException.notFound("event replay", id));
        WebhookRules.requireCancellable(job.status());
        store.setReplayStatus(id, "CANCELLED", null, null);
        String row = store.replayJson(id);
        outbox.publish("platform.webhook_replay_job.cancelled", "webhook_replay_job", id, json.readTree(row));
        return row;
    }

    /** Runs every QUEUED replay job of the current tenant (one transaction per job). */
    public int processQueued() {
        int n = 0;
        for (UUID id : store.queuedReplays()) {
            Boolean ran = tx.execute(s -> run(id));
            if (Boolean.TRUE.equals(ran)) {
                n++;
            }
        }
        return n;
    }

    private boolean run(UUID id) {
        WebhookStore.ReplayRow job = store.replay(id, true).orElse(null);
        if (job == null || !"QUEUED".equals(job.status())) {
            return false;
        }
        store.setReplayStatus(id, "RUNNING", null, null);
        List<String> patterns = job.eventTypes() == null ? List.of() : eventTypes(json.readTree(job.eventTypes()));
        if (patterns.isEmpty()) {
            patterns = store.activeSubscriptionPatterns(job.endpointId());
        }
        int total = 0;
        int replayed = 0;
        for (WebhookStore.DeliveryRow d : store.originalDeliveries(job.endpointId(), job.from(), job.to())) {
            if (patterns.stream().noneMatch(p -> EventTypePattern.matches(p, d.eventType()))) {
                continue;
            }
            total++;
            if (store.insertDeliveryIfAbsent(TenantContext.require(), TenantContext.principal(), Ids.newId(),
                    job.endpointId(), d.eventId(), d.eventType(), d.orderingKey(), d.payload(), id).isPresent()) {
                replayed++;
            }
        }
        store.setReplayStatus(id, "SUCCEEDED", total, replayed);
        outbox.publish("platform.webhook_replay_job.completed", "webhook_replay_job", id,
                json.readTree(store.replayJson(id)));
        return true;
    }

    /** event_types is a JSON array of event types or wildcards, e.g. ["payments.*"]. */
    private static List<String> eventTypes(JsonNode list) {
        List<String> out = new ArrayList<>();
        if (list == null || list.isNull()) {
            return out;
        }
        if (!list.isArray()) {
            throw ApiException.unprocessable("invalid_event_types", "event_types must be an array of event types");
        }
        for (JsonNode t : list) {
            if (!t.isString() || !EventTypePattern.isValid(t.asString())) {
                throw ApiException.unprocessable("invalid_event_types", "Invalid event type pattern " + t);
            }
            out.add(t.asString());
        }
        return out;
    }
}
