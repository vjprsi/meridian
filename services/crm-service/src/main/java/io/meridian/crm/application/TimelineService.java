package io.meridian.crm.application;

import static io.meridian.crm.application.Json.text;

import io.meridian.crm.infrastructure.Rows;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The customer timeline read model: rows are appended by the servicing use cases (same transaction as the change)
 * and by the domain-event projector for other domains; the summary aggregates them for the customer 360.
 */
@Service
public class TimelineService {
    static final String T = "customer_timeline_event";

    private final Rows rows;

    public TimelineService(Rows rows) {
        this.rows = rows;
    }

    /** Appends a timeline entry in the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID customerId, OffsetDateTime at, String eventType, String objectType, UUID objectId,
                       String title, Map<String, ?> detail, int importance) {
        append(customerId, at, eventType, "servicing", objectType, objectId, title, detail, importance);
    }

    void append(UUID customerId, OffsetDateTime at, String eventType, String domain, String objectType, UUID objectId,
                String title, Map<String, ?> detail, int importance) {
        if (customerId == null) {
            return;
        }
        ObjectNode v = rows.object();
        v.put("customer_id", customerId.toString());
        v.put("occurred_at", at.toString());
        v.put("event_type", eventType);
        v.put("source_domain", domain);
        v.put("object_type", objectType);
        if (objectId != null) {
            v.put("object_id", objectId.toString());
        }
        v.put("title", Json.truncate(title, 255));
        if (detail != null) {
            ObjectNode d = v.putObject("detail");
            detail.forEach((k, val) -> {
                if (val != null) {
                    d.put(k, val.toString());
                }
            });
        }
        v.put("importance", importance);
        rows.insert(T, v);
    }

    /**
     * Projects an event of another domain (CloudEvents type + API payload) when it concerns a customer; duplicates
     * (same ce_id) are ignored. Returns whether a row was written.
     */
    @Transactional
    public boolean project(String ceId, String type, JsonNode payload) {
        UUID customerId = customerOf(payload);
        if (customerId == null || type == null) {
            return false;
        }
        if (ceId != null && rows.count("select count(*) from customer_timeline_event where detail ->> 'ce_id' = ?", ceId) > 0) {
            return false;
        }
        String[] parts = type.split("\\.");
        String domain = parts.length > 0 ? parts[0] : "unknown";
        String objectType = parts.length > 1 ? parts[1].toUpperCase(java.util.Locale.ROOT) : null;
        UUID objectId = null;
        try {
            objectId = Json.uuid(payload, "id");
        } catch (IllegalArgumentException ignored) {
            // not a uuid id
        }
        String verb = parts.length > 2 ? parts[2].replace('_', ' ') : "changed";
        String title = (objectType == null ? domain : objectType.replace('_', ' ').toLowerCase(java.util.Locale.ROOT)) + " " + verb;
        Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("ce_id", ceId);
        for (String f : List.of("status", "amount", "currency", "reference", "description")) {
            if (text(payload, f) != null) {
                detail.put(f, text(payload, f));
            }
        }
        int importance = type.contains("fraud") || type.contains("blocked") || type.contains("failed") ? 3 : 1;
        append(customerId, rows.now(), type, domain, objectType, objectId,
                Character.toUpperCase(title.charAt(0)) + title.substring(1), detail, importance);
        return true;
    }

    private static UUID customerOf(JsonNode payload) {
        for (String f : List.of("customer_id", "owner_customer_id", "party_id")) {
            try {
                UUID id = Json.uuid(payload, f);
                if (id != null) {
                    return id;
                }
            } catch (IllegalArgumentException ignored) {
                // not a uuid
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ summary

    @Transactional(readOnly = true)
    public ObjectNode summary(UUID customerId, OffsetDateTime from, OffsetDateTime to) {
        OffsetDateTime end = to == null ? rows.now() : to;
        OffsetDateTime start = from == null ? end.minusDays(90) : from;
        if (!start.isBefore(end)) {
            throw io.meridian.common.error.ApiException.badRequest("invalid_period", "from must be before to");
        }
        ObjectNode s = rows.object();
        s.put("customer_id", customerId.toString());
        s.put("from", start.toString());
        s.put("to", end.toString());
        s.put("total_events", rows.count("""
                select count(*) from customer_timeline_event where customer_id = ? and occurred_at >= ? and occurred_at < ?""",
                customerId, start, end));
        s.set("by_domain", counts("source_domain", customerId, start, end));
        s.set("by_event_type", counts("event_type", customerId, start, end));
        ObjectNode open = s.putObject("open_items");
        open.put("service_cases", rows.count("""
                select count(*) from service_case where customer_id = ? and status not in ('RESOLVED', 'CLOSED', 'CANCELLED')""",
                customerId));
        open.put("breached_cases", rows.count("""
                select count(*) from service_case where customer_id = ? and sla_breached
                and status not in ('RESOLVED', 'CLOSED', 'CANCELLED')""", customerId));
        open.put("complaints", rows.count("""
                select count(*) from complaint where customer_id = ? and status not in ('CLOSED')""", customerId));
        open.put("pinned_notes", rows.count("""
                select count(*) from customer_note where customer_id = ? and is_pinned
                and (expires_at is null or expires_at > now())""", customerId));
        List<ObjectNode> last = rows.query("""
                select row_to_json(t)::text from interaction t where t.customer_id = ? order by t.started_at desc limit 1""",
                customerId);
        if (last.isEmpty()) {
            s.putNull("last_interaction");
        } else {
            ObjectNode li = s.putObject("last_interaction");
            for (String f : List.of("id", "interaction_type", "channel", "started_at", "sentiment", "intent", "subject")) {
                li.set(f, last.getFirst().get(f));
            }
        }
        ArrayNode sentiments = s.putArray("recent_sentiment");
        rows.query("""
                select json_build_object('sentiment', t.sentiment, 'count', count(*))::text from interaction t
                where t.customer_id = ? and t.started_at >= ? and t.started_at < ? and t.sentiment is not null
                group by t.sentiment order by count(*) desc""", customerId, start, end).forEach(sentiments::add);
        ArrayNode highlights = s.putArray("highlights");
        rows.query("""
                select row_to_json(t)::text from customer_timeline_event t
                where t.customer_id = ? and t.occurred_at >= ? and t.occurred_at < ?
                order by t.importance desc, t.occurred_at desc limit 5""", customerId, start, end)
                .forEach(r -> {
                    ObjectNode h = highlights.addObject();
                    for (String f : List.of("id", "occurred_at", "event_type", "source_domain", "object_type", "object_id",
                            "title", "importance")) {
                        h.set(f, r.get(f));
                    }
                });
        return s;
    }

    private ObjectNode counts(String column, UUID customerId, OffsetDateTime start, OffsetDateTime end) {
        ObjectNode o = rows.object();
        List<Object[]> list = new ArrayList<>();
        rows.jdbc().sql("select " + column + ", count(*) from customer_timeline_event where customer_id = ?"
                        + " and occurred_at >= ? and occurred_at < ? group by 1 order by 2 desc, 1")
                .params(customerId, start, end)
                .query((rs, i) -> list.add(new Object[] {rs.getString(1), rs.getInt(2)})).list();
        list.forEach(r -> o.put((String) r[0], (Integer) r[1]));
        return o;
    }
}
