package io.meridian.crm.application;

import static io.meridian.crm.application.Json.text;

import io.meridian.common.error.ApiException;
import io.meridian.common.error.ApiException.FieldError;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.crm.api.generated.model.CaseComment;
import io.meridian.crm.api.generated.model.ServiceCase;
import io.meridian.crm.domain.BusinessCalendar;
import io.meridian.crm.domain.Lifecycles;
import io.meridian.crm.domain.TextAnalysis;
import io.meridian.crm.infrastructure.Rows;
import io.meridian.web.crud.CrudEngine;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Service cases: SLA targets on create (business-hours clock of the matched policy), the case state machine with a
 * pausable resolution clock while waiting for the customer, escalation, comments, conversion into a formal complaint
 * and the SLA monitor (breaches and policy escalation rules).
 */
@Service
public class CaseService {
    static final String T = "service_case";
    static final String SUBJECT = "SERVICE_CASE";
    private static final List<String> PRIORITY_ORDER = List.of("LOW", "MEDIUM", "HIGH", "URGENT");
    private static final int REOPEN_WINDOW_DAYS = 30;

    private final Rows rows;
    private final CrudEngine crud;
    private final Events events;
    private final SlaService sla;
    private final WorkService work;
    private final ComplaintService complaints;
    private final TimelineService timeline;
    private final String defaultCountry;

    public CaseService(Rows rows, CrudEngine crud, Events events, SlaService sla, WorkService work,
                       ComplaintService complaints, TimelineService timeline,
                       @Value("${meridian.crm.default-country:GB}") String defaultCountry) {
        this.rows = rows;
        this.crud = crud;
        this.events = events;
        this.sla = sla;
        this.work = work;
        this.complaints = complaints;
        this.timeline = timeline;
        this.defaultCountry = defaultCountry;
    }

    // ------------------------------------------------------------------ create / update

    @Transactional
    public ObjectNode create(Object body) {
        ObjectNode v = crud.toColumns(T, body);
        OffsetDateTime now = rows.now();
        UUID customerId = Json.uuid(v, "customer_id");
        UUID parent = Json.uuid(v, "parent_case_id");
        if (parent != null) {
            ObjectNode p = rows.find(T, parent).orElseThrow(() ->
                    ApiException.unprocessable("parent_case_not_found", "Parent case " + parent + " does not exist"));
            if (!customerId.toString().equals(text(p, "customer_id"))) {
                throw ApiException.unprocessable("customer_mismatch", "The parent case belongs to another customer");
            }
        }
        if (text(v, "work_queue_id") != null) {
            work.activeQueue(Json.uuid(v, "work_queue_id"));
        }
        if ((text(v, "related_object_type") == null) != (text(v, "related_object_id") == null)) {
            throw new ApiException(422, "invalid_case", "Case is invalid", List.of(new FieldError("related_object_id",
                    "incomplete_reference", "related_object_type and related_object_id go together")));
        }
        String text = text(v, "subject") + " " + (text(v, "description") == null ? "" : text(v, "description"));
        if (text(v, "priority") == null) {
            String suggested = TextAnalysis.priority(TextAnalysis.intent(text), TextAnalysis.sentiment(text));
            v.put("priority", "LOW".equals(suggested) ? "MEDIUM" : suggested);
        }
        if (v.has("tags")) {
            v.set("tags", tags(v.get("tags")));
        }
        Optional<ObjectNode> policy = sla.match("CASE", text(v, "case_type"), null, text(v, "priority"), defaultCountry);
        policy.ifPresent(p -> {
            SlaService.Targets t = sla.targets(p, now);
            v.put("sla_policy_id", text(p, "id"));
            v.put("first_response_due_at", t.firstResponseDue().toString());
            v.put("resolution_due_at", t.resolutionDue().toString());
        });
        v.put("case_number", Numbers.next("CAS", now));
        v.put("status", "NEW");
        ObjectNode row = rows.insert(T, v);
        UUID id = UUID.fromString(text(row, "id"));
        if (text(row, "work_queue_id") != null) {
            enqueue(row, Json.uuid(row, "work_queue_id"));
        }
        events.publish("servicing.service_case.created", T, row, ServiceCase.class);
        timeline.record(customerId, now, "servicing.service_case.created", SUBJECT, id,
                "Case " + text(row, "case_number") + " opened: " + text(row, "subject"),
                Map.of("case_type", text(row, "case_type"), "priority", text(row, "priority"), "channel", text(row, "channel")), 2);
        return row;
    }

    private void enqueue(ObjectNode c, UUID queueId) {
        ObjectNode item = rows.object();
        item.put("work_queue_id", queueId.toString());
        item.put("subject_type", SUBJECT);
        item.put("subject_id", text(c, "id"));
        item.put("title", Json.truncate(text(c, "case_number") + ": " + text(c, "subject"), 255));
        item.put("priority", text(c, "priority"));
        if (text(c, "resolution_due_at") != null) {
            item.put("due_at", text(c, "resolution_due_at"));
        }
        work.enqueue(item);
    }

    @Transactional
    public ObjectNode update(UUID id, String ifMatch, Object body) {
        int expected = Json.parseVersion(ifMatch);
        ObjectNode current = rows.lock(T, id);
        if (Set.of("CLOSED", "CANCELLED").contains(text(current, "status"))) {
            throw ApiException.conflict("case_closed", "A " + text(current, "status") + " case cannot be changed");
        }
        ObjectNode v = crud.toColumns(T, body);
        if (v.has("customer_id") && !text(v, "customer_id").equals(text(current, "customer_id"))) {
            throw ApiException.unprocessable("immutable_field", "customer_id cannot be changed");
        }
        boolean queueAdded = v.has("work_queue_id") && text(current, "work_queue_id") == null;
        if (v.has("work_queue_id") && !queueAdded && !text(v, "work_queue_id").equals(text(current, "work_queue_id"))) {
            throw ApiException.unprocessable("use_escalate", "Move a case to another queue with POST /{id}/escalate");
        }
        if (queueAdded) {
            work.activeQueue(Json.uuid(v, "work_queue_id"));
        }
        if (v.has("tags")) {
            v.set("tags", tags(v.get("tags")));
        }
        ObjectNode row = rows.update(T, id, v, expected).orElseThrow(() -> Json.versionConflict(expected));
        if (queueAdded && !Lifecycles.CASE_TERMINAL.contains(text(row, "status"))) {
            enqueue(row, Json.uuid(row, "work_queue_id"));
        } else if (v.has("priority")) {
            work.syncDue(SUBJECT, id, null, text(row, "priority"));
        }
        return row;
    }

    // ------------------------------------------------------------------ lifecycle

    @Transactional
    public ObjectNode assign(UUID id, UUID assignee) {
        ObjectNode c = rows.lock(T, id);
        ObjectNode v = rows.object();
        v.put("status", Lifecycles.CASE.apply("assign", text(c, "status")));
        v.put("assigned_to", assignee.toString());
        ObjectNode row = rows.update(T, id, v);
        work.followAssignment(SUBJECT, id, assignee);
        events.publish("servicing.service_case.assigned", T, row, ServiceCase.class);
        return row;
    }

    @Transactional
    public ObjectNode start(UUID id) {
        ObjectNode c = rows.lock(T, id);
        ObjectNode v = rows.object();
        v.put("status", Lifecycles.CASE.apply("start", text(c, "status")));
        return statusChanged(id, v);
    }

    @Transactional
    public ObjectNode pendingCustomer(UUID id, String message) {
        ObjectNode c = rows.lock(T, id);
        ObjectNode v = rows.object();
        v.put("status", Lifecycles.CASE.apply("pending-customer", text(c, "status")));
        boolean pause = sla.policy(Json.uuid(c, "sla_policy_id")).map(p -> Json.bool(p, "pause_on_pending_customer")).orElse(false);
        if (pause && text(c, "sla_paused_at") == null) {
            v.put("sla_paused_at", rows.now().toString());
        }
        if (message != null) {
            v.put("status_reason", Json.truncate(message, 1000));
        }
        ObjectNode row = statusChanged(id, v);
        if (message != null && !message.isBlank()) {
            insertComment(row, message, "CUSTOMER", "STAFF", null, null, null);
        }
        return rows.get(T, id);
    }

    @Transactional
    public ObjectNode customerResponded(UUID id, String message) {
        ObjectNode c = rows.lock(T, id);
        ObjectNode v = rows.object();
        v.put("status", Lifecycles.CASE.apply("customer-responded", text(c, "status")));
        resumeClock(c, v);
        if (message != null) {
            v.put("status_reason", Json.truncate(message, 1000));
        }
        ObjectNode row = statusChanged(id, v);
        if (message != null && !message.isBlank()) {
            insertComment(row, message, "CUSTOMER", "CUSTOMER", null, null, null);
        }
        return rows.get(T, id);
    }

    /** Adds the paused business minutes to the resolution deadline and clears the pause. */
    private void resumeClock(ObjectNode c, ObjectNode v) {
        OffsetDateTime pausedAt = Json.ts(c, "sla_paused_at");
        if (pausedAt == null) {
            return;
        }
        ObjectNode policy = sla.policy(Json.uuid(c, "sla_policy_id")).orElse(null);
        BusinessCalendar cal = sla.calendar(policy);
        long paused = cal.minutesBetween(pausedAt, rows.now());
        v.putNull("sla_paused_at");
        v.put("sla_paused_minutes", c.path("sla_paused_minutes").asInt() + (int) paused);
        OffsetDateTime due = Json.ts(c, "resolution_due_at");
        if (due != null && paused > 0) {
            OffsetDateTime newDue = cal.addMinutes(due, paused);
            v.put("resolution_due_at", newDue.toString());
            work.syncDue(SUBJECT, UUID.fromString(text(c, "id")), newDue, null);
        }
    }

    @Transactional
    public ObjectNode escalate(UUID id, UUID queueId, String reason) {
        ObjectNode c = rows.lock(T, id);
        ObjectNode v = rows.object();
        v.put("status", Lifecycles.CASE.apply("escalate", text(c, "status")));
        work.activeQueue(queueId);
        resumeClock(c, v);
        return escalateRow(c, v, queueId, bump(text(c, "priority")), reason);
    }

    private ObjectNode escalateRow(ObjectNode c, ObjectNode v, UUID queueId, String priority, String reason) {
        UUID id = UUID.fromString(text(c, "id"));
        v.put("status", "ESCALATED");
        v.put("escalation_level", c.path("escalation_level").asInt() + 1);
        v.put("escalated_at", rows.now().toString());
        v.put("priority", priority);
        v.put("status_reason", Json.truncate(reason, 1000));
        if (queueId != null) {
            v.put("work_queue_id", queueId.toString());
            v.putNull("assigned_to");
        }
        ObjectNode row = rows.update(T, id, v);
        if (queueId != null) {
            work.requeue(SUBJECT, id, queueId, text(row, "case_number") + ": " + text(row, "subject"), priority,
                    Json.ts(row, "resolution_due_at"), "Escalated: " + reason);
        } else {
            work.syncDue(SUBJECT, id, null, priority);
        }
        events.publish("servicing.service_case.escalated", T, row, ServiceCase.class);
        timeline.record(Json.uuid(row, "customer_id"), rows.now(), "servicing.service_case.escalated", SUBJECT, id,
                "Case " + text(row, "case_number") + " escalated: " + reason, Map.of("priority", priority), 3);
        return row;
    }

    private static String bump(String priority) {
        int i = PRIORITY_ORDER.indexOf(priority);
        return PRIORITY_ORDER.get(Math.min(PRIORITY_ORDER.size() - 1, Math.max(0, i) + 1));
    }

    @Transactional
    public ObjectNode resolve(UUID id, String code, String summary) {
        ObjectNode c = rows.lock(T, id);
        OffsetDateTime now = rows.now();
        ObjectNode v = rows.object();
        v.put("status", Lifecycles.CASE.apply("resolve", text(c, "status")));
        resumeClock(c, v);
        v.put("resolution_code", Json.truncate(code, 64));
        v.put("resolution_summary", summary);
        v.put("resolved_at", now.toString());
        if (text(c, "first_responded_at") == null) {
            v.put("first_responded_at", now.toString());
        }
        String due = v.has("resolution_due_at") ? text(v, "resolution_due_at") : text(c, "resolution_due_at");
        boolean breachedNow = due != null && now.isAfter(OffsetDateTime.parse(due)) && !Json.bool(c, "sla_breached");
        if (breachedNow) {
            v.put("sla_breached", true);
        }
        ObjectNode row = rows.update(T, id, v);
        work.closeFor(SUBJECT, id, true, "Case resolved");
        if (breachedNow) {
            events.publish("servicing.service_case.sla_breached", T, row, ServiceCase.class);
        }
        events.publish("servicing.service_case.resolved", T, row, ServiceCase.class);
        timeline.record(Json.uuid(row, "customer_id"), now, "servicing.service_case.resolved", SUBJECT, id,
                "Case " + text(row, "case_number") + " resolved (" + code + ")", Map.of("resolution_code", code), 2);
        return row;
    }

    @Transactional
    public ObjectNode close(UUID id, Integer csat) {
        ObjectNode c = rows.lock(T, id);
        ObjectNode v = rows.object();
        v.put("status", Lifecycles.CASE.apply("close", text(c, "status")));
        if (csat != null) {
            if (csat < 1 || csat > 5) {
                throw new ApiException(422, "invalid_csat", "csat_score must be 1..5",
                        List.of(new FieldError("csat_score", "out_of_range", "csat_score must be 1..5")));
            }
            v.put("csat_score", csat);
        }
        v.put("closed_at", rows.now().toString());
        ObjectNode row = rows.update(T, id, v);
        events.publish("servicing.service_case.closed", T, row, ServiceCase.class);
        return row;
    }

    @Transactional
    public ObjectNode reopen(UUID id, String reason) {
        ObjectNode c = rows.lock(T, id);
        String to = Lifecycles.CASE.apply("reopen", text(c, "status"));
        OffsetDateTime now = rows.now();
        OffsetDateTime closed = Json.ts(c, "closed_at");
        if (closed != null && closed.plusDays(REOPEN_WINDOW_DAYS).isBefore(now)) {
            throw ApiException.unprocessable("reopen_window_expired",
                    "Cases closed more than " + REOPEN_WINDOW_DAYS + " days ago cannot be reopened; open a new case");
        }
        ObjectNode v = rows.object();
        v.put("status", to);
        v.put("reopened_count", c.path("reopened_count").asInt() + 1);
        v.put("status_reason", Json.truncate(reason, 1000));
        for (String f : List.of("resolved_at", "closed_at", "resolution_code", "resolution_summary", "csat_score")) {
            v.putNull(f);
        }
        sla.policy(Json.uuid(c, "sla_policy_id")).ifPresent(p ->
                v.put("resolution_due_at", sla.calendar(p).addMinutes(now, p.get("resolution_minutes").asLong()).toString()));
        ObjectNode row = rows.update(T, id, v);
        if (text(row, "work_queue_id") != null && work.openItem(SUBJECT, id).isEmpty()) {
            enqueue(row, Json.uuid(row, "work_queue_id"));
        }
        events.publish("servicing.service_case.reopened", T, row, ServiceCase.class);
        timeline.record(Json.uuid(row, "customer_id"), now, "servicing.service_case.reopened", SUBJECT, id,
                "Case " + text(row, "case_number") + " reopened: " + reason, Map.of(), 2);
        return row;
    }

    @Transactional
    public ObjectNode cancel(UUID id, String reason) {
        ObjectNode c = rows.lock(T, id);
        ObjectNode v = rows.object();
        v.put("status", Lifecycles.CASE.apply("cancel", text(c, "status")));
        v.put("status_reason", Json.truncate(reason, 1000));
        v.putNull("sla_paused_at");
        v.put("closed_at", rows.now().toString());
        ObjectNode row = statusChanged(id, v);
        work.closeFor(SUBJECT, id, false, "Case cancelled: " + reason);
        return row;
    }

    private ObjectNode statusChanged(UUID id, ObjectNode v) {
        ObjectNode row = rows.update(T, id, v);
        events.publish("servicing.service_case.status_changed", T, row, ServiceCase.class);
        return row;
    }

    // ------------------------------------------------------------------ complaint conversion

    @Transactional
    public ObjectNode convertToComplaint(UUID id, String category) {
        ObjectNode c = rows.lock(T, id);
        Lifecycles.CASE.apply("convert-to-complaint", text(c, "status"));
        if (text(c, "complaint_id") != null) {
            throw ApiException.conflict("already_converted", "The case was already raised as complaint " + text(c, "complaint_id"));
        }
        ObjectNode cv = rows.object();
        cv.put("customer_id", text(c, "customer_id"));
        cv.put("service_case_id", id.toString());
        cv.put("category", category);
        cv.put("channel", text(c, "channel"));
        cv.put("summary", Json.truncate(text(c, "subject"), 500));
        if (text(c, "description") != null) {
            cv.put("details", text(c, "description"));
        }
        if (text(c, "related_object_type") != null) {
            cv.put("related_object_type", text(c, "related_object_type"));
            cv.put("related_object_id", text(c, "related_object_id"));
        }
        ObjectNode complaint = complaints.create(cv);
        ObjectNode v = rows.object();
        v.put("complaint_id", text(complaint, "id"));
        ObjectNode row = rows.update(T, id, v);
        insertComment(row, "Raised as formal complaint " + text(complaint, "complaint_number"), "INTERNAL", "SYSTEM",
                null, null, null);
        return complaint;
    }

    // ------------------------------------------------------------------ comments

    @Transactional
    public ObjectNode addComment(UUID id, JsonNode req) {
        ObjectNode c = rows.lock(T, id);
        String status = text(c, "status");
        if (!Lifecycles.CASE.allows("comment", status)) {
            throw ApiException.conflict("case_closed", "A " + status + " case does not accept comments; reopen it first");
        }
        String body = text(req, "body");
        if (body == null || body.isBlank()) {
            throw ApiException.badRequest("validation_failed", "body is required");
        }
        String visibility = text(req, "visibility");
        String authorType = text(req, "author_type") == null ? "STAFF" : text(req, "author_type");
        List<FieldError> errors = new ArrayList<>();
        if ("CUSTOMER".equals(authorType) && !"CUSTOMER".equals(visibility)) {
            errors.add(new FieldError("visibility", "invalid_visibility", "Customer comments are visible to the customer"));
        }
        UUID session = Json.uuid(req, "copilot_session_id");
        if ("COPILOT".equals(authorType) && session == null) {
            errors.add(new FieldError("copilot_session_id", "required", "A COPILOT comment must reference its copilot session"));
        }
        if (session != null && rows.find("copilot_session", session).isEmpty()) {
            errors.add(new FieldError("copilot_session_id", "not_found", "Copilot session does not exist"));
        }
        JsonNode attachments = req.get("attachments");
        if (attachments != null && attachments.isArray()) {
            for (JsonNode a : attachments) {
                try {
                    UUID.fromString(a.asString());
                } catch (IllegalArgumentException e) {
                    errors.add(new FieldError("attachments", "invalid_document_id", "attachments are document ids"));
                    break;
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(422, "invalid_comment", "Comment is invalid", errors);
        }
        ObjectNode comment = insertComment(c, body, visibility, authorType, Json.uuid(req, "author_id"),
                attachments, session);
        if ("CUSTOMER".equals(authorType) && "PENDING_CUSTOMER".equals(status)) {
            ObjectNode v = rows.object();
            v.put("status", Lifecycles.CASE.apply("customer-responded", status));
            resumeClock(c, v);
            statusChanged(id, v);
        }
        return comment;
    }

    /** Inserts a comment; a customer-visible staff or copilot reply is the case's first response. */
    private ObjectNode insertComment(ObjectNode c, String body, String visibility, String authorType, UUID authorId,
                                     JsonNode attachments, UUID session) {
        UUID caseId = UUID.fromString(text(c, "id"));
        ObjectNode v = rows.object();
        v.put("service_case_id", caseId.toString());
        v.put("visibility", visibility);
        v.put("author_type", authorType);
        if (authorId != null) {
            v.put("author_id", authorId.toString());
        }
        v.put("author_name", Json.truncate(TenantContext.principal(), 64));
        v.put("body", body);
        if (attachments != null && attachments.isArray()) {
            v.set("attachments", attachments);
        }
        if (session != null) {
            v.put("copilot_session_id", session.toString());
        }
        ObjectNode comment = rows.insert("case_comment", v);
        events.publish("servicing.case_comment.added", "case_comment", comment, CaseComment.class);
        if ("CUSTOMER".equals(visibility) && Set.of("STAFF", "COPILOT").contains(authorType)
                && text(c, "first_responded_at") == null) {
            ObjectNode fr = rows.object();
            fr.put("first_responded_at", rows.now().toString());
            if ("NEW".equals(text(c, "status"))) {
                fr.put("status", "OPEN");
            }
            rows.update(T, caseId, fr);
            c.put("first_responded_at", text(fr, "first_responded_at"));
        }
        return comment;
    }

    // ------------------------------------------------------------------ SLA monitor

    /**
     * Flags breached cases (first response or resolution target passed while the clock runs) and applies the policy's
     * escalation rules as elapsed time crosses each {@code at_percent}. Idempotent: each breach and rule fires once.
     */
    @Transactional
    public ObjectNode slaCheck() {
        OffsetDateTime now = rows.now();
        List<ObjectNode> open = rows.query("""
                select row_to_json(t)::text from service_case t
                where t.status not in ('RESOLVED', 'CLOSED', 'CANCELLED') and t.sla_policy_id is not null
                  and t.sla_paused_at is null
                order by t.resolution_due_at nulls last limit 1000 for update skip locked""");
        ArrayNode breached = JsonNodeFactory.instance.arrayNode();
        ArrayNode escalated = JsonNodeFactory.instance.arrayNode();
        int atRisk = 0;
        for (ObjectNode c : open) {
            UUID id = UUID.fromString(text(c, "id"));
            ObjectNode policy = sla.policy(Json.uuid(c, "sla_policy_id")).orElse(null);
            if (policy == null) {
                continue;
            }
            BusinessCalendar cal = sla.calendar(policy);
            OffsetDateTime firstDue = Json.ts(c, "first_response_due_at");
            OffsetDateTime due = Json.ts(c, "resolution_due_at");
            boolean isBreached = (text(c, "first_responded_at") == null && firstDue != null && now.isAfter(firstDue))
                    || (due != null && now.isAfter(due));
            ObjectNode current = c;
            if (isBreached && !Json.bool(c, "sla_breached")) {
                ObjectNode v = rows.object();
                v.put("sla_breached", true);
                current = rows.update(T, id, v);
                events.publish("servicing.service_case.sla_breached", T, current, ServiceCase.class);
                timeline.record(Json.uuid(c, "customer_id"), now, "servicing.service_case.sla_breached", SUBJECT, id,
                        "Case " + text(c, "case_number") + " breached its SLA", Map.of(), 4);
                breached.add(id.toString());
            }
            long total = due == null ? 0 : cal.minutesBetween(Json.ts(c, "created_at"), due) + c.path("sla_paused_minutes").asLong();
            long elapsed = cal.minutesBetween(Json.ts(c, "created_at"), now);
            long percent = total <= 0 ? 0 : elapsed * 100 / total;
            if (!isBreached && percent >= policy.path("at_risk_percent").asInt(80)) {
                atRisk++;
            }
            JsonNode rules = policy.get("escalation_rules");
            if (rules != null && rules.isArray() && total > 0) {
                int reached = 0;
                JsonNode rule = null;
                for (JsonNode r : rules) {
                    if (percent >= r.path("at_percent").asLong()) {
                        reached++;
                        rule = r;
                    }
                }
                int level = current.path("escalation_level").asInt();
                if (reached > level && rule != null && Lifecycles.CASE.allows("escalate", text(current, "status"))) {
                    UUID queue = rule.hasNonNull("work_queue_id") ? UUID.fromString(rule.get("work_queue_id").asString()) : null;
                    String priority = rule.hasNonNull("priority") ? rule.get("priority").asString() : bump(text(current, "priority"));
                    ObjectNode v = rows.object();
                    ObjectNode after = escalateRow(current, v, queue, priority, "SLA escalation rule at " + rule.get("at_percent").asInt() + "%");
                    ObjectNode lv = rows.object();
                    lv.put("escalation_level", reached);
                    rows.update(T, id, lv);
                    escalated.add(text(after, "id"));
                }
            }
        }
        ObjectNode out = rows.object();
        out.put("checked_at", now.toString());
        out.put("checked", open.size());
        out.set("breached", breached);
        out.set("escalated", escalated);
        out.put("at_risk", atRisk);
        return out;
    }

    private static ArrayNode tags(JsonNode n) {
        Set<String> out = new LinkedHashSet<>();
        Json.strings(n).forEach(t -> {
            String k = t.trim().toLowerCase(Locale.ROOT);
            if (!k.isEmpty()) {
                out.add(Json.truncate(k, 64));
            }
        });
        ArrayNode a = JsonNodeFactory.instance.arrayNode();
        out.forEach(a::add);
        return a;
    }
}
