package io.meridian.crm.application;

import static io.meridian.crm.application.Json.text;

import io.meridian.common.error.ApiException;
import io.meridian.common.error.ApiException.FieldError;
import io.meridian.crm.api.generated.model.Interaction;
import io.meridian.crm.domain.TextAnalysis;
import io.meridian.crm.infrastructure.Rows;
import io.meridian.web.crud.CrudEngine;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

/** Interactions on any channel (with sentiment / intent detection) and free-text customer notes. */
@Service
public class InteractionService {
    static final String T = "interaction";
    static final String NOTES = "customer_note";
    private static final Set<String> NOTE_CATEGORIES = Set.of("GENERAL", "ALERT", "VULNERABILITY", "PREFERENCE", "COMPLIANCE");
    private static final Set<String> OUTBOUND_TYPES = Set.of("CALL_OUTBOUND", "EMAIL_OUTBOUND", "SMS", "PUSH", "LETTER");
    private static final Set<String> INBOUND_TYPES = Set.of("CALL_INBOUND", "EMAIL_INBOUND");

    private final Rows rows;
    private final CrudEngine crud;
    private final Events events;
    private final TimelineService timeline;

    public InteractionService(Rows rows, CrudEngine crud, Events events, TimelineService timeline) {
        this.rows = rows;
        this.crud = crud;
        this.events = events;
        this.timeline = timeline;
    }

    @Transactional
    public ObjectNode create(Object body) {
        ObjectNode v = crud.toColumns(T, body);
        validate(v);
        analyse(v);
        ObjectNode row = rows.insert(T, v);
        UUID id = UUID.fromString(text(row, "id"));
        UUID caseId = Json.uuid(row, "service_case_id");
        if (caseId != null && "OUTBOUND".equals(text(row, "direction"))) {
            // A staff contact with the customer about the case is the case's first response.
            rows.execute("""
                    update service_case set first_responded_at = ?, status = case when status = 'NEW' then 'OPEN' else status end
                    where id = ? and first_responded_at is null""", Json.ts(row, "started_at"), caseId);
        }
        events.publish("servicing.interaction.logged", T, row, Interaction.class);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("channel", text(row, "channel"));
        detail.put("direction", text(row, "direction"));
        detail.put("sentiment", text(row, "sentiment"));
        detail.put("intent", text(row, "intent"));
        int importance = Set.of("VERY_NEGATIVE", "NEGATIVE").contains(text(row, "sentiment")) ? 2 : 1;
        timeline.record(Json.uuid(row, "customer_id"), Json.ts(row, "started_at"), "servicing.interaction.logged", "INTERACTION",
                id, title(row), detail, importance);
        return row;
    }

    @Transactional
    public ObjectNode update(UUID id, String ifMatch, Object body) {
        int expected = Json.parseVersion(ifMatch);
        ObjectNode current = rows.lock(T, id);
        ObjectNode v = crud.toColumns(T, body);
        if (v.has("customer_id") && !text(v, "customer_id").equals(text(current, "customer_id"))) {
            throw ApiException.unprocessable("immutable_field", "customer_id cannot be changed");
        }
        ObjectNode merged = current.deepCopy();
        merged.setAll(v);
        validate(merged);
        if (v.has("summary") || v.has("subject")) {
            analyse(merged);
            v.set("sentiment", merged.get("sentiment"));
            v.set("intent", merged.get("intent"));
        }
        if (v.has("started_at") || v.has("ended_at")) {
            v.set("duration_seconds", merged.get("duration_seconds"));
        }
        return rows.update(T, id, v, expected).orElseThrow(() -> Json.versionConflict(expected));
    }

    private void validate(ObjectNode v) {
        List<FieldError> errors = new ArrayList<>();
        String type = text(v, "interaction_type");
        String direction = text(v, "direction");
        if (OUTBOUND_TYPES.contains(type) && !"OUTBOUND".equals(direction)) {
            errors.add(new FieldError("direction", "inconsistent_direction", type + " is an outbound interaction"));
        }
        if (INBOUND_TYPES.contains(type) && !"INBOUND".equals(direction)) {
            errors.add(new FieldError("direction", "inconsistent_direction", type + " is an inbound interaction"));
        }
        OffsetDateTime start = Json.ts(v, "started_at");
        OffsetDateTime end = Json.ts(v, "ended_at");
        if (start != null && end != null) {
            if (end.isBefore(start)) {
                errors.add(new FieldError("ended_at", "before_start", "ended_at must not be before started_at"));
            } else if (!v.hasNonNull("duration_seconds") || v.get("duration_seconds").asInt() < 0) {
                v.put("duration_seconds", (int) Duration.between(start, end).toSeconds());
            }
        }
        if (v.hasNonNull("duration_seconds") && v.get("duration_seconds").asInt() < 0) {
            errors.add(new FieldError("duration_seconds", "negative", "duration_seconds cannot be negative"));
        }
        UUID customer = Json.uuid(v, "customer_id");
        for (String ref : List.of("service_case_id:service_case", "complaint_id:complaint")) {
            String[] p = ref.split(":");
            UUID id = Json.uuid(v, p[0]);
            if (id != null) {
                ObjectNode r = rows.find(p[1], id).orElse(null);
                if (r == null) {
                    errors.add(new FieldError(p[0], "not_found", p[1].replace('_', ' ') + " does not exist"));
                } else if (!customer.toString().equals(text(r, "customer_id"))) {
                    errors.add(new FieldError(p[0], "customer_mismatch", "The " + p[1].replace('_', ' ') + " belongs to another customer"));
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(422, "invalid_interaction", "Interaction is invalid", errors);
        }
    }

    private static void analyse(ObjectNode v) {
        String text = (text(v, "subject") == null ? "" : text(v, "subject")) + " " + (text(v, "summary") == null ? "" : text(v, "summary"));
        v.put("sentiment", TextAnalysis.sentiment(text));
        v.put("intent", TextAnalysis.intent(text));
    }

    private static String title(ObjectNode row) {
        String t = text(row, "interaction_type").replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
        String s = text(row, "subject");
        return Character.toUpperCase(t.charAt(0)) + t.substring(1) + " via " + text(row, "channel")
                + (s == null ? "" : ": " + s);
    }

    // ------------------------------------------------------------------ notes

    @Transactional
    public ObjectNode createNote(Object body) {
        ObjectNode v = crud.toColumns(NOTES, body);
        validateNote(v);
        if ("VULNERABILITY".equals(text(v, "category")) || "ALERT".equals(text(v, "category"))) {
            if (!v.has("is_pinned")) {
                v.put("is_pinned", true);
            }
        }
        ObjectNode row = rows.insert(NOTES, v);
        if (Json.bool(row, "is_pinned")) {
            timeline.record(Json.uuid(row, "customer_id"), rows.now(), "servicing.customer_note.pinned", "CUSTOMER_NOTE",
                    Json.uuid(row, "id"), "Pinned " + (text(row, "category") == null ? "GENERAL" : text(row, "category")).toLowerCase(java.util.Locale.ROOT)
                            + " note added", Map.of(), 2);
        }
        return row;
    }

    @Transactional
    public ObjectNode updateNote(UUID id, String ifMatch, Object body) {
        int expected = Json.parseVersion(ifMatch);
        ObjectNode current = rows.lock(NOTES, id);
        ObjectNode v = crud.toColumns(NOTES, body);
        if (v.has("customer_id") && !text(v, "customer_id").equals(text(current, "customer_id"))) {
            throw ApiException.unprocessable("immutable_field", "customer_id cannot be changed");
        }
        ObjectNode merged = current.deepCopy();
        merged.setAll(v);
        validateNote(merged);
        if (v.has("category")) {
            v.set("category", merged.get("category"));
        }
        return rows.update(NOTES, id, v, expected).orElseThrow(() -> Json.versionConflict(expected));
    }

    private void validateNote(ObjectNode v) {
        List<FieldError> errors = new ArrayList<>();
        if (text(v, "body") == null || text(v, "body").isBlank()) {
            errors.add(new FieldError("body", "required", "body must not be blank"));
        }
        if (text(v, "category") != null) {
            String c = text(v, "category").toUpperCase(java.util.Locale.ROOT);
            if (!NOTE_CATEGORIES.contains(c)) {
                errors.add(new FieldError("category", "invalid_category", "category must be one of " + NOTE_CATEGORIES));
            }
            v.put("category", c);
        }
        OffsetDateTime expires = Json.ts(v, "expires_at");
        if (expires != null && !v.has("id") && expires.isBefore(rows.now())) {
            errors.add(new FieldError("expires_at", "in_past", "expires_at must be in the future"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(422, "invalid_note", "Note is invalid", errors);
        }
    }
}
