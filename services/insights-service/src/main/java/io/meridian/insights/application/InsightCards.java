package io.meridian.insights.application;

import static io.meridian.insights.application.Json.text;

import io.meridian.insights.api.generated.model.FinancialInsight;
import io.meridian.insights.domain.InsightRules;
import io.meridian.insights.domain.InsightRules.Candidate;
import io.meridian.insights.domain.Lifecycles;
import io.meridian.insights.infrastructure.ExternalServices;
import io.meridian.insights.infrastructure.Rows;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

/** Writes insight cards (business de-duplication) and delivers them through notification-service (fail-open). */
@Component
public class InsightCards {
    static final String T = "financial_insight";

    private final Rows rows;
    private final Events events;
    private final ExternalServices external;
    private final int expiryDays;
    private final String channel;

    public InsightCards(Rows rows, Events events, ExternalServices external,
                        @Value("${meridian.insights.insight-expiry-days:30}") int expiryDays,
                        @Value("${meridian.insights.notification-channel:PUSH}") String channel) {
        this.rows = rows;
        this.events = events;
        this.external = external;
        this.expiryDays = expiryDays;
        this.channel = channel;
    }

    /**
     * Records a card unless one with the same dedupe key exists for the customer; publishes insight.generated and,
     * when {@code notify}, delivers it. Empty when it was a duplicate.
     */
    public Optional<ObjectNode> raise(UUID customerId, Candidate c, boolean notify) {
        rows.advisoryLock("insight:" + customerId + ":" + c.dedupeKey());
        if (rows.scalar("select 1 from financial_insight where customer_id = ? and dedupe_key = ?", Integer.class,
                customerId, c.dedupeKey()).isPresent()) {
            return Optional.empty();
        }
        OffsetDateTime now = rows.now();
        ObjectNode v = rows.object();
        v.put("customer_id", customerId.toString());
        v.put("insight_type", c.type());
        v.put("severity", c.severity());
        v.put("title", Json.truncate(c.title(), 160));
        v.put("body", Json.truncate(c.body(), 1000));
        v.set("data", rows.json().valueToTree(c.data()));
        if (c.actionLabel() != null) {
            v.put("action_label", c.actionLabel());
            v.put("action_deeplink", c.deeplink());
        }
        v.put("related_type", c.relatedType());
        if (c.relatedId() != null) {
            v.put("related_id", c.relatedId().toString());
        }
        v.put("dedupe_key", Json.truncate(c.dedupeKey(), 128));
        v.put("delivery_status", "PENDING");
        v.put("generated_at", now.toString());
        v.put("expires_at", now.plusDays(expiryDays).toString());
        v.put("model_version", InsightRules.VERSION);
        ObjectNode row = rows.insert(T, v);
        events.publish("engagement.insight.generated", T, row, FinancialInsight.class);
        return Optional.of(notify ? deliver(row) : row);
    }

    /** Sends the card through notification-service; DELIVERED on success, unchanged (PENDING) on failure. */
    public ObjectNode deliver(ObjectNode insight) {
        if (!Lifecycles.INSIGHT.allows("deliver", text(insight, "delivery_status"))) {
            return insight;
        }
        UUID id = Json.uuid(insight, "id");
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", text(insight, "title"));
        vars.put("body", text(insight, "body"));
        vars.put("insight_id", id.toString());
        vars.put("insight_type", text(insight, "insight_type"));
        if (text(insight, "action_deeplink") != null) {
            vars.put("deeplink", text(insight, "action_deeplink"));
        }
        UUID notification = external.notifyCustomer(Json.uuid(insight, "customer_id"), "INSIGHT_" + text(insight, "insight_type"),
                channel, vars, "insight:" + id);
        if (notification == null) {
            return insight;
        }
        ObjectNode v = rows.object();
        v.put("delivery_status", "DELIVERED");
        v.put("delivered_at", rows.now().toString());
        v.put("notification_id", notification.toString());
        ObjectNode row = rows.update(T, id, v);
        events.publish("engagement.insight.delivered", T, row, FinancialInsight.class);
        return row;
    }

}
