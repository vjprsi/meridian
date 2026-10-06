package io.meridian.customer.application;

import static io.meridian.customer.application.Json.text;
import static io.meridian.customer.application.Json.today;

import io.meridian.common.error.ApiException;
import io.meridian.common.error.ApiException.FieldError;
import io.meridian.customer.domain.SegmentRule;
import io.meridian.customer.infrastructure.Rows;
import io.meridian.web.crud.CrudEngine;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Segments and their assignment. Automatic segments carry validated criteria and are (re)evaluated over all
 * open customers: matching customers are assigned, rule-based assignments of customers that stopped matching
 * are ended (valid_to), and when several automatic segments match the lower priority value wins.
 */
@Service
public class SegmentService {
    static final String T = "customer_segment";
    static final String A = "customer_segment_assignment";

    private final Rows rows;
    private final CrudEngine crud;
    private final Guards guards;
    private final JsonMapper json;

    public SegmentService(Rows rows, CrudEngine crud, Guards guards, JsonMapper json) {
        this.rows = rows;
        this.crud = crud;
        this.guards = guards;
        this.json = json;
    }

    @Transactional
    public ObjectNode create(Object body) {
        ObjectNode v = crud.toColumns(T, body);
        validate(v);
        v.put("code", text(v, "code").trim().toUpperCase(java.util.Locale.ROOT));
        return rows.insert(T, v);
    }

    @Transactional
    public ObjectNode update(UUID id, String ifMatch, Object body) {
        int expected = CustomerService.parseVersion(ifMatch);
        ObjectNode current = rows.lock(T, id);
        ObjectNode v = crud.toColumns(T, body);
        if (v.has("code") && !text(v, "code").trim().equalsIgnoreCase(text(current, "code"))) {
            throw ApiException.unprocessable("immutable_field", "code cannot be changed");
        }
        v.remove("code");
        ObjectNode merged = current.deepCopy();
        merged.setAll(v);
        validate(merged);
        return rows.update(T, id, v, expected).orElseThrow(() -> CustomerService.versionConflict(expected));
    }

    private static void validate(ObjectNode v) {
        JsonNode criteria = v.get("criteria");
        boolean automatic = v.path("is_automatic").asBoolean();
        if (automatic || (criteria != null && !criteria.isNull())) {
            List<FieldError> errors = SegmentRule.validate(criteria);
            if (!errors.isEmpty()) {
                throw new ApiException(422, "invalid_criteria", "Segment criteria are invalid", errors);
            }
        }
        if (v.has("priority") && v.get("priority").asInt() < 0) {
            throw ApiException.unprocessable("invalid_priority", "priority must not be negative");
        }
    }

    /** Manual assignment: the segment must be active and not already held by the customer. */
    @Transactional
    public ObjectNode assign(UUID customerId, Object body) {
        guards.openCustomer(customerId);
        ObjectNode v = crud.toColumns(A, body);
        v.put("customer_id", customerId.toString());
        ObjectNode segment = rows.get(T, UUID.fromString(text(v, "customer_segment_id")));
        if (!segment.path("is_active").asBoolean()) {
            throw ApiException.conflict("segment_inactive", "Segment " + text(segment, "code") + " is not active");
        }
        if (text(v, "valid_to") != null && text(v, "valid_to").compareTo(text(v, "valid_from")) < 0) {
            throw ApiException.unprocessable("invalid_period", "valid_to must not be before valid_from");
        }
        int existing = rows.jdbc().sql("""
                select count(*) from customer_segment_assignment where customer_id = ? and customer_segment_id = ?
                and (valid_to is null or valid_to >= current_date)""")
                .params(customerId, UUID.fromString(text(v, "customer_segment_id"))).query(Integer.class).single();
        if (existing > 0) {
            throw ApiException.conflict("already_assigned", "Customer already holds this segment");
        }
        v.put("assigned_by_rule", false);
        return rows.insert(A, v);
    }

    @Transactional
    public JsonNode evaluate(UUID segmentId) {
        ObjectNode segment = rows.lock(T, segmentId);
        if (!segment.path("is_active").asBoolean()) {
            throw ApiException.conflict("segment_inactive", "Segment " + text(segment, "code") + " is not active");
        }
        if (!segment.path("is_automatic").asBoolean()) {
            throw ApiException.conflict("segment_not_automatic", "Only automatic segments can be evaluated");
        }
        JsonNode criteria = segment.get("criteria");
        List<FieldError> errors = SegmentRule.validate(criteria);
        if (!errors.isEmpty()) {
            throw new ApiException(422, "invalid_criteria", "Segment criteria are invalid", errors);
        }
        int priority = segment.path("priority").asInt(100);
        LocalDate today = today();
        // Active rule-based assignments of every customer, with the priority of the segment held.
        Map<UUID, Map<UUID, Integer>> held = new HashMap<>();
        rows.jdbc().sql("""
                select a.customer_id, a.customer_segment_id, s.priority from customer_segment_assignment a
                join customer_segment s on s.id = a.customer_segment_id
                where a.assigned_by_rule and (a.valid_to is null or a.valid_to >= current_date) and s.is_automatic and s.is_active""")
                .query((rs, i) -> held.computeIfAbsent(rs.getObject(1, UUID.class), k -> new HashMap<>())
                        .put(rs.getObject(2, UUID.class), rs.getInt(3)))
                .list();
        int evaluated = 0;
        int matched = 0;
        int added = 0;
        int removed = 0;
        int superseded = 0;
        for (ObjectNode c : rows.query("select row_to_json(t)::text from customer t where t.status not in ('CLOSED', 'DECEASED')")) {
            evaluated++;
            UUID customerId = UUID.fromString(c.get("id").asString());
            Map<UUID, Integer> mine = held.getOrDefault(customerId, Map.of());
            boolean has = mine.containsKey(segmentId);
            boolean matches = SegmentRule.matches(criteria, c, today);
            boolean outranked = mine.entrySet().stream()
                    .anyMatch(e -> !e.getKey().equals(segmentId) && e.getValue() < priority);
            if (matches) {
                matched++;
            }
            if (matches && !has && !outranked) {
                ObjectNode v = rows.object();
                v.put("customer_id", customerId.toString());
                v.put("customer_segment_id", segmentId.toString());
                v.put("assigned_by_rule", true);
                v.put("valid_from", today.toString());
                rows.insert(A, v);
                added++;
                // Lower-precedence automatic segments give way to this one.
                for (Map.Entry<UUID, Integer> e : mine.entrySet()) {
                    if (!e.getKey().equals(segmentId) && e.getValue() > priority) {
                        superseded += endAssignment(customerId, e.getKey(), today);
                    }
                }
            } else if (has && (!matches || outranked)) {
                removed += endAssignment(customerId, segmentId, today);
            }
        }
        ObjectNode r = json.createObjectNode();
        r.put("customer_segment_id", segmentId.toString());
        r.put("code", text(segment, "code"));
        r.put("evaluated", evaluated);
        r.put("matched", matched);
        r.put("added", added);
        r.put("removed", removed);
        r.put("superseded", superseded);
        r.put("evaluated_on", today.toString());
        return r;
    }

    /** Ends a rule-based assignment as of yesterday; one that only started today is withdrawn entirely. */
    private int endAssignment(UUID customerId, UUID segmentId, LocalDate today) {
        int withdrawn = rows.execute("""
                delete from customer_segment_assignment where customer_id = ? and customer_segment_id = ?
                and assigned_by_rule and valid_from >= ?""", customerId, segmentId, today);
        int ended = rows.execute("""
                update customer_segment_assignment set valid_to = ? where customer_id = ? and customer_segment_id = ?
                and assigned_by_rule and (valid_to is null or valid_to >= ?)""", today.minusDays(1), customerId, segmentId, today);
        return withdrawn + ended;
    }
}
