package io.meridian.crm.application;

import static io.meridian.crm.application.Json.text;

import io.meridian.common.error.ApiException;
import io.meridian.common.error.ApiException.FieldError;
import io.meridian.crm.api.generated.model.WorkItem;
import io.meridian.crm.domain.Lifecycles;
import io.meridian.crm.infrastructure.Rows;
import io.meridian.web.crud.CrudEngine;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Work queues and items: pull-based routing ({@code next}: priority, then due time, then age, restricted to items
 * whose required skills the caller has and to queues where the caller is below the per-agent cap), manual
 * assignment, start, release, completion and cancellation, plus queue statistics.
 */
@Service
public class WorkService {
    static final String Q = "work_queue";
    static final String T = "work_item";
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_-]{1,39}$");
    private static final Set<String> SUBJECTS = Set.of("SERVICE_CASE", "COMPLAINT", "KYC_REVIEW", "AML_ALERT", "DISPUTE",
            "RECON_EXCEPTION");
    private static final String PRIORITY_RANK =
            "(case t.priority when 'URGENT' then 0 when 'HIGH' then 1 when 'MEDIUM' then 2 else 3 end)";

    private final Rows rows;
    private final CrudEngine crud;
    private final Events events;

    public WorkService(Rows rows, CrudEngine crud, Events events) {
        this.rows = rows;
        this.crud = crud;
        this.events = events;
    }

    // ------------------------------------------------------------------ queues

    @Transactional
    public ObjectNode createQueue(Object body) {
        ObjectNode v = crud.toColumns(Q, body);
        validateQueue(v);
        return rows.insert(Q, v);
    }

    @Transactional
    public ObjectNode updateQueue(UUID id, String ifMatch, Object body) {
        int expected = Json.parseVersion(ifMatch);
        ObjectNode current = rows.lock(Q, id);
        ObjectNode v = crud.toColumns(Q, body);
        if (v.has("code") && !text(v, "code").equals(text(current, "code"))) {
            throw ApiException.unprocessable("immutable_field", "code cannot be changed");
        }
        ObjectNode merged = current.deepCopy();
        merged.setAll(v);
        validateQueue(merged);
        if (v.has("required_skills")) {
            v.set("required_skills", merged.get("required_skills"));
        }
        return rows.update(Q, id, v, expected).orElseThrow(() -> Json.versionConflict(expected));
    }

    private static void validateQueue(ObjectNode v) {
        List<FieldError> errors = new ArrayList<>();
        String code = text(v, "code");
        if (code == null || !CODE.matcher(code).matches()) {
            errors.add(new FieldError("code", "invalid_code", "code must be 2-40 upper-case letters, digits, '_' or '-'"));
        }
        if (Json.integer(v, "max_items_per_agent", 20) < 1) {
            errors.add(new FieldError("max_items_per_agent", "must_be_positive", "max_items_per_agent must be >= 1"));
        }
        if ("SKILLS_BASED".equals(text(v, "routing")) && skills(v.get("required_skills")).isEmpty()) {
            errors.add(new FieldError("required_skills", "required", "A SKILLS_BASED queue needs required_skills"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(422, "invalid_work_queue", "Work queue is invalid", errors);
        }
        if (v.has("required_skills") && !v.get("required_skills").isNull()) {
            v.set("required_skills", skillArray(skills(v.get("required_skills"))));
        }
    }

    /** Queue statistics: counts by status and priority, ageing, SLA risk and per-assignee load. */
    @Transactional(readOnly = true)
    public ObjectNode stats(UUID queueId) {
        ObjectNode q = rows.get(Q, queueId);
        OffsetDateTime now = rows.now();
        ObjectNode s = rows.object();
        s.put("work_queue_id", queueId.toString());
        s.put("code", text(q, "code"));
        s.put("routing", text(q, "routing"));
        s.put("generated_at", now.toString());
        ObjectNode byStatus = s.putObject("by_status");
        for (String st : List.of("QUEUED", "ASSIGNED", "IN_PROGRESS", "DONE", "CANCELLED")) {
            byStatus.put(st, rows.count("select count(*) from work_item where work_queue_id = ? and status = ?", queueId, st));
        }
        ObjectNode byPriority = s.putObject("open_by_priority");
        for (String p : List.of("URGENT", "HIGH", "MEDIUM", "LOW")) {
            byPriority.put(p, rows.count("""
                    select count(*) from work_item where work_queue_id = ? and priority = ?
                    and status in ('QUEUED', 'ASSIGNED', 'IN_PROGRESS')""", queueId, p));
        }
        Double oldest = rows.jdbc().sql("""
                select extract(epoch from (? - min(created_at))) / 60 from work_item
                where work_queue_id = ? and status = 'QUEUED'""").params(now, queueId).query(Double.class).optional().orElse(null);
        Double avg = rows.jdbc().sql("""
                select extract(epoch from (? - avg_created)) / 60 from (
                  select to_timestamp(avg(extract(epoch from created_at))) as avg_created from work_item
                  where work_queue_id = ? and status = 'QUEUED') x""").params(now, queueId).query(Double.class).optional().orElse(null);
        s.put("oldest_queued_minutes", oldest == null ? 0 : Math.round(oldest));
        s.put("average_queued_minutes", avg == null ? 0 : Math.round(avg));
        s.put("breached", rows.count("""
                select count(*) from work_item where work_queue_id = ? and status in ('QUEUED', 'ASSIGNED', 'IN_PROGRESS')
                and due_at < ?""", queueId, now));
        // At risk: not yet due but more than 80% of the time between queueing and the due time has elapsed.
        s.put("at_risk", rows.count("""
                select count(*) from work_item where work_queue_id = ? and status in ('QUEUED', 'ASSIGNED', 'IN_PROGRESS')
                and due_at >= ? and due_at > created_at
                and extract(epoch from (? - created_at)) >= 0.8 * extract(epoch from (due_at - created_at))""",
                queueId, now, now));
        s.put("due_within_hour", rows.count("""
                select count(*) from work_item where work_queue_id = ? and status in ('QUEUED', 'ASSIGNED', 'IN_PROGRESS')
                and due_at >= ? and due_at < ?""", queueId, now, now.plusHours(1)));
        s.put("completed_last_24h", rows.count("""
                select count(*) from work_item where work_queue_id = ? and status = 'DONE' and completed_at >= ?""",
                queueId, now.minusHours(24)));
        int cap = q.get("max_items_per_agent").asInt();
        ArrayNode load = s.putArray("assignees");
        rows.query("""
                select json_build_object('assigned_to', assigned_to,
                    'assigned', count(*) filter (where status = 'ASSIGNED'),
                    'in_progress', count(*) filter (where status = 'IN_PROGRESS'))::text
                from work_item where work_queue_id = ? and status in ('ASSIGNED', 'IN_PROGRESS') and assigned_to is not null
                group by assigned_to order by count(*) desc""", queueId).forEach(a -> {
                    int open = a.get("assigned").asInt() + a.get("in_progress").asInt();
                    a.put("capacity", cap);
                    a.put("utilisation_percent", Math.round(100.0 * open / cap));
                    load.add(a);
                });
        return s;
    }

    // ------------------------------------------------------------------ items

    @Transactional
    public ObjectNode createItem(Object body) {
        ObjectNode v = crud.toColumns(T, body);
        return enqueue(v);
    }

    /** Validates and inserts a QUEUED item (also used by cases and complaints). */
    public ObjectNode enqueue(ObjectNode v) {
        UUID queueId = Json.uuid(v, "work_queue_id");
        activeQueue(queueId);
        String subject = text(v, "subject_type");
        if (subject == null || !SUBJECTS.contains(subject)) {
            throw new ApiException(422, "invalid_work_item", "Work item is invalid",
                    List.of(new FieldError("subject_type", "invalid_subject_type", "subject_type must be one of " + SUBJECTS)));
        }
        if (rows.count("""
                select count(*) from work_item where subject_type = ? and subject_id = ? and work_queue_id = ?
                and status in ('QUEUED', 'ASSIGNED', 'IN_PROGRESS')""", subject, Json.uuid(v, "subject_id"), queueId) > 0) {
            throw ApiException.conflict("duplicate_work_item", "The subject already has an open item in this queue");
        }
        if (v.has("required_skills")) {
            v.set("required_skills", skillArray(skills(v.get("required_skills"))));
        }
        v.put("status", "QUEUED");
        ObjectNode row = rows.insert(T, v);
        events.publish("servicing.work_item.created", T, row, WorkItem.class);
        return row;
    }

    @Transactional
    public ObjectNode updateItem(UUID id, String ifMatch, Object body) {
        int expected = Json.parseVersion(ifMatch);
        ObjectNode current = rows.lock(T, id);
        if (!Lifecycles.ITEM_OPEN.contains(text(current, "status"))) {
            throw ApiException.conflict("work_item_closed", "A " + text(current, "status") + " work item cannot be changed");
        }
        ObjectNode v = crud.toColumns(T, body);
        for (String immutable : List.of("subject_type", "subject_id")) {
            if (v.has(immutable) && !text(v, immutable).equals(text(current, immutable))) {
                throw ApiException.unprocessable("immutable_field", immutable + " cannot be changed");
            }
        }
        if (v.has("work_queue_id") && !text(v, "work_queue_id").equals(text(current, "work_queue_id"))) {
            if (!"QUEUED".equals(text(current, "status"))) {
                throw ApiException.conflict("work_item_assigned", "Release the item before moving it to another queue");
            }
            activeQueue(Json.uuid(v, "work_queue_id"));
        }
        if (v.has("required_skills")) {
            v.set("required_skills", skillArray(skills(v.get("required_skills"))));
        }
        return rows.update(T, id, v, expected).orElseThrow(() -> Json.versionConflict(expected));
    }

    /** Pulls the best eligible QUEUED item for the caller and assigns it, skipping rows other agents hold. */
    @Transactional
    public ObjectNode next(UUID queueId, UUID assignee, JsonNode skillsNode) {
        if (assignee == null) {
            throw ApiException.badRequest("validation_failed", "assignee_id is required");
        }
        if (queueId != null) {
            ObjectNode q = activeQueue(queueId);
            if ("MANUAL".equals(text(q, "routing"))) {
                throw ApiException.unprocessable("manual_routing", "Items of a MANUAL queue are assigned by a supervisor");
            }
        }
        String skills = skillArray(skills(skillsNode)).toString();
        List<ObjectNode> found = rows.query("""
                select row_to_json(t)::text from work_item t join work_queue q on q.id = t.work_queue_id
                where t.status = 'QUEUED' and q.is_active and q.routing <> 'MANUAL'
                  and (cast(? as uuid) is null or t.work_queue_id = cast(? as uuid))
                  and (coalesce(t.required_skills, '[]'::jsonb) || coalesce(q.required_skills, '[]'::jsonb)) <@ cast(? as jsonb)
                  and (select count(*) from work_item w where w.work_queue_id = q.id and w.assigned_to = ?
                       and w.status in ('ASSIGNED', 'IN_PROGRESS')) < q.max_items_per_agent
                order by %s, t.due_at nulls last, t.created_at, t.id
                limit 1 for update of t skip locked""".formatted(PRIORITY_RANK),
                queueId, queueId, skills, assignee);
        if (found.isEmpty()) {
            if (queueId != null && atCapacity(queueId, assignee)) {
                throw ApiException.conflict("agent_at_capacity", "You already hold the maximum number of items in this queue");
            }
            throw ApiException.notFound("eligible work item", queueId == null ? "any queue" : queueId);
        }
        ObjectNode item = found.getFirst();
        return assignRow(item, assignee, "next");
    }

    @Transactional
    public ObjectNode assign(UUID id, UUID assignee) {
        ObjectNode item = rows.lock(T, id);
        if (assignee == null) {
            throw ApiException.badRequest("validation_failed", "assigned_to is required");
        }
        if (assignee.toString().equals(text(item, "assigned_to")) && !"QUEUED".equals(text(item, "status"))) {
            return item;
        }
        if (atCapacity(Json.uuid(item, "work_queue_id"), assignee)) {
            throw ApiException.conflict("agent_at_capacity", "The assignee already holds the maximum number of items in this queue");
        }
        return assignRow(item, assignee, "assign");
    }

    private ObjectNode assignRow(ObjectNode item, UUID assignee, String action) {
        String to = Lifecycles.WORK_ITEM.apply("assign", text(item, "status"));
        ObjectNode v = rows.object();
        v.put("status", to);
        v.put("assigned_to", assignee.toString());
        v.put("assigned_at", rows.now().toString());
        v.putNull("started_at");
        ObjectNode row = rows.update(T, UUID.fromString(text(item, "id")), v);
        events.publish("servicing.work_item.assigned", T, row, WorkItem.class);
        return row;
    }

    @Transactional
    public ObjectNode start(UUID id) {
        ObjectNode item = rows.lock(T, id);
        ObjectNode v = rows.object();
        v.put("status", Lifecycles.WORK_ITEM.apply("start", text(item, "status")));
        v.put("started_at", rows.now().toString());
        return rows.update(T, id, v);
    }

    @Transactional
    public ObjectNode release(UUID id, String reason) {
        ObjectNode item = rows.lock(T, id);
        ObjectNode v = rows.object();
        v.put("status", Lifecycles.WORK_ITEM.apply("release", text(item, "status")));
        v.putNull("assigned_to");
        v.putNull("assigned_at");
        v.putNull("started_at");
        v.put("release_count", item.path("release_count").asInt() + 1);
        if (reason != null) {
            v.put("last_release_reason", Json.truncate(reason, 255));
        }
        ObjectNode row = rows.update(T, id, v);
        events.publish("servicing.work_item.released", T, row, WorkItem.class);
        return row;
    }

    @Transactional
    public ObjectNode complete(UUID id) {
        ObjectNode item = rows.lock(T, id);
        ObjectNode v = rows.object();
        v.put("status", Lifecycles.WORK_ITEM.apply("complete", text(item, "status")));
        v.put("completed_at", rows.now().toString());
        ObjectNode row = rows.update(T, id, v);
        events.publish("servicing.work_item.completed", T, row, WorkItem.class);
        return row;
    }

    @Transactional
    public ObjectNode cancel(UUID id, String reason) {
        ObjectNode item = rows.lock(T, id);
        ObjectNode v = rows.object();
        v.put("status", Lifecycles.WORK_ITEM.apply("cancel", text(item, "status")));
        v.put("completed_at", rows.now().toString());
        v.put("status_reason", Json.truncate(reason, 255));
        ObjectNode row = rows.update(T, id, v);
        events.publish("servicing.work_item.completed", T, row, WorkItem.class);
        return row;
    }

    // ------------------------------------------------------------------ subject hooks (cases, complaints)

    public Optional<ObjectNode> openItem(String subjectType, UUID subjectId) {
        return rows.query("""
                select row_to_json(t)::text from work_item t where t.subject_type = ? and t.subject_id = ?
                and t.status in ('QUEUED', 'ASSIGNED', 'IN_PROGRESS') order by t.id desc limit 1 for update""",
                subjectType, subjectId).stream().findFirst();
    }

    /** Assigns the subject's open item (if any) to the subject's new owner, ignoring the per-agent cap. */
    public void followAssignment(String subjectType, UUID subjectId, UUID assignee) {
        openItem(subjectType, subjectId).ifPresent(i -> {
            if (!assignee.toString().equals(text(i, "assigned_to"))) {
                assignRow(i, assignee, "assign");
            }
        });
    }

    /** Closes the subject's open items: DONE when the subject was worked to an end, CANCELLED otherwise. */
    public void closeFor(String subjectType, UUID subjectId, boolean done, String reason) {
        rows.query("""
                select row_to_json(t)::text from work_item t where t.subject_type = ? and t.subject_id = ?
                and t.status in ('QUEUED', 'ASSIGNED', 'IN_PROGRESS') for update""", subjectType, subjectId).forEach(i -> {
                    UUID id = UUID.fromString(text(i, "id"));
                    ObjectNode v = rows.object();
                    boolean asDone = done && !"QUEUED".equals(text(i, "status"));
                    v.put("status", asDone ? "DONE" : "CANCELLED");
                    v.put("completed_at", rows.now().toString());
                    if (!asDone) {
                        v.put("status_reason", Json.truncate(reason, 255));
                    }
                    events.publish("servicing.work_item.completed", T, rows.update(T, id, v), WorkItem.class);
                });
    }

    /** Moves the subject's open item to another queue (escalation): the old item is cancelled, a new one queued. */
    public void requeue(String subjectType, UUID subjectId, UUID queueId, String title, String priority,
                        OffsetDateTime dueAt, String reason) {
        closeFor(subjectType, subjectId, false, reason);
        ObjectNode v = rows.object();
        v.put("work_queue_id", queueId.toString());
        v.put("subject_type", subjectType);
        v.put("subject_id", subjectId.toString());
        v.put("title", Json.truncate(title, 255));
        v.put("priority", priority);
        if (dueAt != null) {
            v.put("due_at", dueAt.toString());
        }
        enqueue(v);
    }

    public void syncDue(String subjectType, UUID subjectId, OffsetDateTime dueAt, String priority) {
        openItem(subjectType, subjectId).ifPresent(i -> {
            ObjectNode v = rows.object();
            if (dueAt != null) {
                v.put("due_at", dueAt.toString());
            }
            if (priority != null) {
                v.put("priority", priority);
            }
            rows.update(T, UUID.fromString(text(i, "id")), v);
        });
    }

    // ------------------------------------------------------------------ helpers

    public ObjectNode activeQueue(UUID queueId) {
        if (queueId == null) {
            throw ApiException.badRequest("validation_failed", "work_queue_id is required");
        }
        ObjectNode q = rows.find(Q, queueId).orElseThrow(() ->
                ApiException.unprocessable("work_queue_not_found", "Work queue " + queueId + " does not exist"));
        if (!q.path("is_active").asBoolean()) {
            throw ApiException.unprocessable("queue_inactive", "Work queue " + text(q, "code") + " is inactive");
        }
        return q;
    }

    private boolean atCapacity(UUID queueId, UUID assignee) {
        ObjectNode q = rows.get(Q, queueId);
        return rows.count("""
                select count(*) from work_item where work_queue_id = ? and assigned_to = ?
                and status in ('ASSIGNED', 'IN_PROGRESS')""", queueId, assignee) >= q.get("max_items_per_agent").asInt();
    }

    static Set<String> skills(JsonNode n) {
        Set<String> out = new LinkedHashSet<>();
        Json.strings(n).forEach(s -> {
            String k = s.trim().toUpperCase(Locale.ROOT);
            if (!k.isEmpty()) {
                out.add(k);
            }
        });
        return out;
    }

    private static ArrayNode skillArray(Set<String> skills) {
        ArrayNode a = tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        skills.forEach(a::add);
        return a;
    }
}
