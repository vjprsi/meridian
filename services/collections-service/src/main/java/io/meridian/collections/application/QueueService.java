package io.meridian.collections.application;

import io.meridian.collections.domain.QueueDistribution;
import io.meridian.collections.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Work queues: prioritised case lists, distribution of new cases and full rebalancing across collectors. */
@Service
public class QueueService {
    private final Rows rows;
    private final Events events;

    public QueueService(Rows rows, Events events) {
        this.rows = rows;
        this.events = events;
    }

    /** Open cases of the queue, highest priority first (keyset cursor over priority and id). */
    @Transactional(readOnly = true)
    public ObjectNode cases(UUID queueId, Integer limit, String cursor) {
        rows.get("collection_queue", queueId);
        int lim = limit == null ? 50 : Math.max(1, Math.min(limit, 200));
        Integer afterPriority = null;
        UUID afterId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                String[] p = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":", 2);
                afterPriority = Integer.parseInt(p[0]);
                afterId = UUID.fromString(p[1]);
            } catch (RuntimeException e) {
                throw ApiException.badRequest("invalid_cursor", "cursor is malformed");
            }
        }
        List<ObjectNode> list = rows.query("""
                select row_to_json(c)::text from collection_case c where collection_queue_id = ?
                  and status not in ('RESOLVED', 'CLOSED')
                  and (?::int is null or (coalesce(priority_score, 0), id) < (?::int, ?::uuid))
                order by coalesce(priority_score, 0) desc, id desc limit ?""",
                queueId, afterPriority, afterPriority, afterId, lim + 1);
        boolean more = list.size() > lim;
        List<ObjectNode> data = more ? list.subList(0, lim) : list;
        ObjectNode page = rows.object();
        ArrayNode arr = page.putArray("data");
        data.forEach(arr::add);
        page.put("has_more", more);
        if (more) {
            ObjectNode last = data.getLast();
            String c = (last.path("priority_score").isNull() ? 0 : last.path("priority_score").asInt()) + ":"
                    + Rows.str(last, "id");
            page.put("next_cursor", Base64.getUrlEncoder().withoutPadding().encodeToString(c.getBytes(StandardCharsets.UTF_8)));
        } else {
            page.putNull("next_cursor");
        }
        return page;
    }

    /** Redistributes the queue's open cases evenly across its collectors (capacity respected, minimal moves). */
    @Transactional
    public ObjectNode rebalance(UUID queueId) {
        ObjectNode q = rows.lock("collection_queue", queueId);
        if (!Rows.bool(q, "is_active")) {
            throw ApiException.conflict("queue_inactive", "Queue " + Rows.str(q, "code") + " is inactive");
        }
        if ("MANUAL".equals(Rows.str(q, "assignment_mode"))) {
            throw ApiException.conflict("manual_queue", "Queue " + Rows.str(q, "code") + " is assigned manually");
        }
        List<String> agents = CaseService.agents(q);
        List<ObjectNode> open = openCases(queueId);
        List<QueueDistribution.QueuedCase> queued = new ArrayList<>();
        open.forEach(c -> queued.add(new QueueDistribution.QueuedCase(Rows.uuid(c, "id"), Rows.str(c, "assigned_to"))));
        QueueDistribution.Result r = QueueDistribution.rebalance(agents, Rows.integer(q, "max_cases_per_agent"), queued);
        for (QueueDistribution.QueuedCase c : queued) {
            String to = r.assignment().get(c.id());
            if (!java.util.Objects.equals(to, c.assignedTo())) {
                assignTo(c.id(), to);
            }
        }
        ObjectNode out = rows.object();
        out.put("queue_id", queueId.toString());
        out.put("cases", open.size());
        out.put("moved", r.moved());
        out.put("unassigned", r.unassigned());
        ObjectNode per = out.putObject("per_agent");
        r.perAgent().forEach(per::put);
        return out;
    }

    /** Assigns the queue's unassigned open cases to the least-loaded collectors (EOD distribution). */
    @Transactional
    public int distribute(UUID queueId) {
        ObjectNode q = rows.lock("collection_queue", queueId);
        String mode = Rows.str(q, "assignment_mode");
        List<String> agents = CaseService.agents(q);
        if (!Rows.bool(q, "is_active") || "MANUAL".equals(mode) || agents.isEmpty()) {
            return 0;
        }
        List<ObjectNode> open = openCases(queueId);
        Map<String, Integer> load = new HashMap<>();
        for (ObjectNode c : open) {
            String a = Rows.str(c, "assigned_to");
            if (a != null) {
                load.merge(a, 1, Integer::sum);
            }
        }
        int assigned = 0;
        for (ObjectNode c : open) {
            if (Rows.str(c, "assigned_to") != null || Rows.str(c, "agency_name") != null) {
                continue;
            }
            String agent = QueueDistribution.leastLoaded(agents, load, Rows.integer(q, "max_cases_per_agent"));
            if (agent == null) {
                break;
            }
            load.merge(agent, 1, Integer::sum);
            assignTo(Rows.uuid(c, "id"), agent);
            assigned++;
        }
        return assigned;
    }

    private List<ObjectNode> openCases(UUID queueId) {
        return rows.query("""
                select row_to_json(c)::text from collection_case c where collection_queue_id = ?
                  and status not in ('RESOLVED', 'CLOSED')
                order by coalesce(priority_score, 0) desc, opened_at, id for update""", queueId);
    }

    private void assignTo(UUID caseId, String agent) {
        ObjectNode v = rows.object();
        if (agent == null) {
            v.putNull("assigned_to");
        } else {
            v.put("assigned_to", agent);
        }
        ObjectNode updated = rows.update(CaseService.TABLE, caseId, v);
        events.publish("lending.collection_case.assigned", CaseService.TABLE, updated);
    }
}
