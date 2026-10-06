package io.meridian.notification.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.events.OutboxWriter;
import io.meridian.notification.domain.DeliveryPolicy;
import io.meridian.notification.domain.NotificationLifecycle;
import io.meridian.notification.domain.TemplateRenderer;
import io.meridian.notification.infrastructure.NotificationStore;
import io.meridian.web.crud.CrudEngine;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Notification use cases: direct and templated sends (preferences, quiet hours, dedupe), resend, cancel, mark-read,
 * provider receipts and the due-work sweep. Each state change runs in its own transaction; provider calls happen in
 * {@link DeliveryService#dispatch} after the notification row is committed.
 */
@Service
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    /** Channel order used when a templated send does not name a channel. */
    static final List<String> DEFAULT_CHANNEL_ORDER = List.of("PUSH", "IN_APP", "SMS", "EMAIL", "WHATSAPP", "USSD", "VOICE");
    private static final List<String> SERVER_COLUMNS = List.of("status", "sent_at", "delivered_at", "read_at",
            "failure_reason", "provider_message_id", "cost", "cost_currency");

    private final NotificationStore store;
    private final CrudEngine crud;
    private final DeliveryService delivery;
    private final OutboxWriter outbox;
    private final JsonMapper json;
    private final TransactionTemplate tx;

    public NotificationService(NotificationStore store, CrudEngine crud, DeliveryService delivery, OutboxWriter outbox,
                               JsonMapper json, TransactionTemplate tx) {
        this.store = store;
        this.crud = crud;
        this.delivery = delivery;
        this.outbox = outbox;
        this.json = json;
        this.tx = tx;
    }

    /** Result of a submission: the row and whether it was newly created (false = deduplicated). */
    public record Submitted(String row, boolean created) {}

    // ------------------------------------------------------------------ send

    /** POST /v1/notifications: an already-rendered message. */
    public Submitted create(Object body) {
        ObjectNode values = crud.toColumns("notification", body);
        SERVER_COLUMNS.forEach(values::remove);
        if (values.hasNonNull("notification_template_id")
                && store.json("notification_template", UUID.fromString(values.get("notification_template_id").asString())).isEmpty()) {
            throw ApiException.unprocessable("template_not_found", "notification_template_id does not exist");
        }
        return submit(values, null);
    }

    /** Validates, applies preferences / quiet hours / dedupe, stores, then dispatches if due now. */
    public Submitted submit(ObjectNode values, UUID forcedProviderId) {
        String channel = values.get("channel").asString();
        String destination = values.path("destination").asString(null);
        String problem = DeliveryPolicy.destinationProblem(channel, destination);
        if (problem != null) {
            throw ApiException.unprocessable("invalid_destination", problem);
        }
        Instant now = Instant.now();
        if (values.hasNonNull("expires_at") && !OffsetDateTime.parse(values.get("expires_at").asString()).toInstant().isAfter(now)) {
            throw ApiException.unprocessable("already_expired", "expires_at must be in the future");
        }
        if (!values.hasNonNull("locale")) {
            values.put("locale", "en");
        }
        Submitted stored = tx.execute(s -> {
            String recipientType = values.get("recipient_type").asString();
            UUID recipientId = values.hasNonNull("recipient_id") ? UUID.fromString(values.get("recipient_id").asString()) : null;
            if (values.hasNonNull("source_event_id")) {
                Optional<String> dup = store.duplicate(values.get("source_event_id").asString(), channel, recipientType,
                        recipientId, destination);
                if (dup.isPresent()) {
                    return new Submitted(dup.get(), false);
                }
            }
            String category = values.get("category").asString();
            String status = "QUEUED";
            Instant sendAt = values.hasNonNull("scheduled_at")
                    ? OffsetDateTime.parse(values.get("scheduled_at").asString()).toInstant() : null;
            Optional<JsonNode> pref = forcedProviderId != null ? Optional.empty()
                    : store.preference(recipientType, recipientId, category, channel).map(json::readTree);
            if (pref.isPresent() && !pref.get().path("is_enabled").asBoolean(true) && !DeliveryPolicy.mandatory(category)) {
                status = "SUPPRESSED";
                values.put("failure_reason", "recipient_opted_out");
            } else {
                if (pref.isPresent() && DeliveryPolicy.respectsQuietHours(category)) {
                    JsonNode p = pref.get();
                    Instant reference = sendAt != null && sendAt.isAfter(now) ? sendAt : now;
                    Optional<Instant> until = DeliveryPolicy.quietUntil(reference, p.path("quiet_hours_start").asString(null),
                            p.path("quiet_hours_end").asString(null), p.path("timezone").asString(null));
                    if (until.isPresent()) {
                        sendAt = until.get();
                    }
                }
                if (sendAt != null && sendAt.isAfter(now)) {
                    status = "SCHEDULED";
                    values.put("scheduled_at", OffsetDateTime.ofInstant(sendAt, ZoneOffset.UTC).toString());
                }
            }
            values.put("id", Ids.newId().toString());
            values.put("status", status);
            return new Submitted(crud.insert("notification", values), true);
        });
        if (!stored.created()) {
            return stored;
        }
        UUID id = UUID.fromString(json.readTree(stored.row()).get("id").asString());
        if ("QUEUED".equals(values.get("status").asString())) {
            return new Submitted(delivery.dispatch(id, forcedProviderId, false, Instant.now()), true);
        }
        return stored;
    }

    /** A request to render an ACTIVE template and send it. */
    public record TemplatedSend(String templateCode, String recipientType, UUID recipientId, String channel,
                                Map<String, Object> variables, String category, String sourceEventType,
                                String sourceEventId, String locale) {}

    public Submitted sendTemplated(TemplatedSend req) {
        Map<String, Object> vars = req.variables() == null ? Map.of() : req.variables();
        String locale = req.locale() != null ? req.locale()
                : vars.get("locale") instanceof String l ? l
                : req.recipientId() == null ? "en"
                : store.preferredLocale(req.recipientType(), req.recipientId()).orElse("en");
        List<String> channels;
        if (req.channel() != null) {
            channels = List.of(req.channel());
        } else {
            List<String> available = store.activeTemplateChannels(req.templateCode());
            channels = DEFAULT_CHANNEL_ORDER.stream().filter(available::contains).toList();
        }
        if (channels.isEmpty()) {
            throw ApiException.unprocessable("template_not_found",
                    "No ACTIVE template " + req.templateCode() + " exists");
        }
        // Choose the first channel with an active template and a resolvable destination; prefer channels the
        // recipient has not opted out of (opted-out sends are still recorded as SUPPRESSED).
        record Candidate(String channel, JsonNode template, String destination, boolean optedOut) {}
        List<Candidate> candidates = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (String ch : channels) {
            Optional<String> t = store.activeTemplate(req.templateCode(), ch, DeliveryPolicy.localeFallbacks(locale));
            if (t.isEmpty()) {
                problems.add(ch + ": no ACTIVE template");
                continue;
            }
            JsonNode template = json.readTree(t.get());
            String destination = resolveDestination(ch, vars, req.recipientId());
            if (destination == null) {
                problems.add(ch + ": no destination");
                continue;
            }
            String category = req.category() != null ? req.category() : template.get("category").asString();
            boolean optedOut = !DeliveryPolicy.mandatory(category)
                    && store.preference(req.recipientType(), req.recipientId(), category, ch)
                            .map(p -> !json.readTree(p).path("is_enabled").asBoolean(true)).orElse(false);
            candidates.add(new Candidate(ch, template, destination, optedOut));
        }
        if (candidates.isEmpty()) {
            boolean noTemplate = problems.stream().allMatch(p -> p.endsWith("no ACTIVE template"));
            throw ApiException.unprocessable(noTemplate ? "template_not_found" : "destination_required",
                    "Cannot send " + req.templateCode() + ": " + String.join("; ", problems)
                            + (noTemplate ? "" : " (pass variables.destination or variables.destinations.<CHANNEL>)"));
        }
        Candidate c = candidates.stream().filter(x -> !x.optedOut()).findFirst().orElse(candidates.getFirst());
        String ch = c.channel();
        JsonNode template = c.template();
        String subject;
        String body;
        try {
            subject = TemplateRenderer.render(text(template, "subject"), vars, false, true);
            body = TemplateRenderer.render(template.get("body").asString(), vars, "EMAIL".equals(ch), true);
        } catch (TemplateRenderer.MissingVariablesException e) {
            throw missingVariables(e);
        }
        ObjectNode values = json.createObjectNode();
        values.put("recipient_type", req.recipientType());
        if (req.recipientId() != null) {
            values.put("recipient_id", req.recipientId().toString());
        }
        values.put("channel", ch);
        values.put("destination", c.destination());
        values.put("notification_template_id", template.get("id").asString());
        values.put("category", req.category() != null ? req.category() : template.get("category").asString());
        values.put("locale", template.get("locale").asString());
        if (subject != null) {
            values.put("subject", subject.length() > 255 ? subject.substring(0, 255) : subject);
        }
        values.put("body", body);
        if (vars.get("data") instanceof Map<?, ?> data) {
            values.set("data", json.valueToTree(data));
        }
        if (vars.get("priority") instanceof String p) {
            values.put("priority", p);
        }
        if (req.sourceEventType() != null) {
            values.put("source_event_type", req.sourceEventType());
        }
        if (req.sourceEventId() != null) {
            values.put("source_event_id", req.sourceEventId());
        }
        return submit(values, null);
    }

    /**
     * Destination for a channel: {@code variables.destinations.<CHANNEL>}, then {@code variables.destination}, then
     * channel-typical fields (email, phone/mobile, device_token); IN_APP falls back to the recipient id (inbox).
     */
    static String resolveDestination(String channel, Map<String, Object> vars, UUID recipientId) {
        if (vars.get("destinations") instanceof Map<?, ?> m && m.get(channel) instanceof String s && !s.isBlank()) {
            return s;
        }
        if (vars.get("destination") instanceof String s && !s.isBlank()) {
            return s;
        }
        List<String> fields = switch (channel) {
            case "EMAIL" -> List.of("email", "email_address");
            case "SMS", "WHATSAPP", "VOICE", "USSD" -> List.of("phone", "mobile", "msisdn", "phone_number");
            case "PUSH" -> List.of("device_token", "push_token");
            default -> List.of();
        };
        for (String f : fields) {
            if (vars.get(f) instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        if ("IN_APP".equals(channel) && recipientId != null) {
            return recipientId.toString();
        }
        return null;
    }

    // ------------------------------------------------------------------ lifecycle actions

    public String resend(UUID id) {
        tx.executeWithoutResult(s -> {
            JsonNode n = lock(id);
            String status = n.get("status").asString();
            if (!NotificationLifecycle.resendable(status)) {
                throw ApiException.conflict("invalid_transition",
                        "Only FAILED or EXPIRED notifications can be resent; this one is " + status);
            }
            store.update(id, "QUEUED", "failure_reason = null, scheduled_at = null"
                    + ("EXPIRED".equals(status) ? ", expires_at = null" : ""));
        });
        return delivery.dispatch(id, null, true, Instant.now());
    }

    public String cancel(UUID id) {
        return tx.execute(s -> {
            JsonNode n = lock(id);
            String status = n.get("status").asString();
            if (!NotificationLifecycle.cancellable(status)) {
                throw ApiException.conflict("invalid_transition",
                        "Only QUEUED or SCHEDULED notifications can be cancelled; this one is " + status);
            }
            store.update(id, "SUPPRESSED", "failure_reason = 'cancelled'");
            return row(id);
        });
    }

    public String markRead(UUID id) {
        return tx.execute(s -> {
            JsonNode n = lock(id);
            if (!"IN_APP".equals(n.get("channel").asString())) {
                throw ApiException.unprocessable("not_in_app", "Only IN_APP notifications can be marked as read");
            }
            String status = n.get("status").asString();
            if ("READ".equals(status)) {
                return row(id);
            }
            if (!NotificationLifecycle.readable(status)) {
                throw ApiException.conflict("invalid_transition", "A " + status + " notification cannot be marked read");
            }
            store.update(id, "READ", "read_at = now(), delivered_at = coalesce(delivered_at, now())");
            return row(id);
        });
    }

    /**
     * Delivery receipt from a provider. Payload: {@code provider_message_id} (or {@code message_id}), {@code status}
     * (provider vocabulary, e.g. delivered/undelivered/read) and optional {@code error}. Out-of-order or repeated
     * receipts that would move the status backwards are ignored.
     */
    public Map<String, Object> providerCallback(String provider, Map<String, Object> payload) {
        Object mid = payload.get("provider_message_id") != null ? payload.get("provider_message_id")
                : payload.get("message_id") != null ? payload.get("message_id") : payload.get("MessageSid");
        if (mid == null) {
            throw ApiException.unprocessable("provider_message_id_required", "payload.provider_message_id is required");
        }
        String target = NotificationLifecycle.receiptStatus(payload.get("status") == null ? null : String.valueOf(payload.get("status")));
        if (target == null) {
            throw ApiException.unprocessable("unknown_status", "Unrecognised receipt status " + payload.get("status"));
        }
        return tx.execute(s -> {
            JsonNode n = json.readTree(store.byProviderMessageId(String.valueOf(mid))
                    .orElseThrow(() -> ApiException.notFound("notification with provider message", mid)));
            UUID id = UUID.fromString(n.get("id").asString());
            String status = n.get("status").asString();
            boolean applied = false;
            if (!status.equals(target) && NotificationLifecycle.canMove(status, target)) {
                switch (target) {
                    case "DELIVERED" -> {
                        store.update(id, "DELIVERED", "delivered_at = now()");
                        outbox.publish("platform.notification.delivered", "notification", id, json.readTree(row(id)));
                    }
                    case "READ" -> {
                        boolean wasDelivered = n.hasNonNull("delivered_at");
                        store.update(id, "READ", "read_at = now(), delivered_at = coalesce(delivered_at, now())");
                        if (!wasDelivered) {
                            outbox.publish("platform.notification.delivered", "notification", id, json.readTree(row(id)));
                        }
                    }
                    case "FAILED" -> {
                        Object err = payload.get("error") != null ? payload.get("error") : payload.get("error_code");
                        String reason = "provider " + provider + ": " + (err == null ? "undelivered" : err);
                        store.update(id, "FAILED", "failure_reason = ?", reason.length() > 255 ? reason.substring(0, 255) : reason);
                        outbox.publish("platform.notification.failed", "notification", id, json.readTree(row(id)));
                    }
                    default -> { }
                }
                applied = true;
            }
            return Map.of("notification_id", id.toString(), "status", json.readTree(row(id)).get("status").asString(),
                    "applied", applied);
        });
    }

    // ------------------------------------------------------------------ background

    /** Sends notifications whose scheduled time or retry time has come. Returns how many were processed. */
    public int processDue(Instant now) {
        int processed = 0;
        for (UUID id : store.due(now, 200)) {
            try {
                delivery.dispatch(id, null, false, now);
                processed++;
            } catch (RuntimeException e) {
                log.warn("Dispatch of notification {} failed: {}", id, e.toString());
            }
        }
        return processed;
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode lock(UUID id) {
        return json.readTree(store.lockNotification(id).orElseThrow(() -> ApiException.notFound("notification", id)));
    }

    private String row(UUID id) {
        return store.json("notification", id).orElseThrow();
    }

    static ApiException missingVariables(TemplateRenderer.MissingVariablesException e) {
        return new ApiException(422, "missing_variables", e.getMessage(), e.names().stream()
                .map(n -> new ApiException.FieldError("variables." + n, "missing", "Variable " + n + " is required"))
                .toList());
    }

    private static String text(JsonNode n, String f) {
        return n.hasNonNull(f) ? n.get(f).asString() : null;
    }
}
