package io.meridian.card.application;

import io.meridian.card.domain.ControlRules;
import io.meridian.card.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import io.meridian.common.page.Cursor;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.List;
import org.springframework.http.ResponseEntity;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Card controls and spend limits: create / update / delete semantics with validation and change events. */
@Service
public class ControlService {
    private final Rows rows;
    private final Events events;
    private final CrudEngine crud;

    public ControlService(Rows rows, Events events, CrudEngine crud) {
        this.rows = rows;
        this.events = events;
        this.crud = crud;
    }

    /** PATCH of a control or limit: validated against the merged row, updated and announced in one transaction. */
    @Transactional
    public <T> ResponseEntity<T> update(String table, UUID id, String ifMatch, Object body, JsonNode req, Class<T> type) {
        if ("card_control".equals(table)) {
            checkControlUpdate(id, req);
        } else {
            checkLimitUpdate(id, req);
        }
        ResponseEntity<T> res = crud.update(table, type, body, ifMatch, Crud.params().with("id", id));
        events.publish("card_control".equals(table) ? "cards.card_control.changed" : "cards.card_spend_limit.changed",
                table, rows.get(table, id));
        return res;
    }

    // ------------------------------------------------------------------ controls

    @Transactional
    public JsonNode createControl(JsonNode req) {
        validateScope(req);
        ControlRules.validateControl(ControlSpecs.from(req));
        ObjectNode row = rows.insert("card_control", rows.columns("card_control", req));
        events.publish("cards.card_control.changed", "card_control", row);
        return row;
    }

    /** Validates a control PATCH against the stored row (scope cannot move). */
    @Transactional(readOnly = true)
    public void checkControlUpdate(UUID id, JsonNode req) {
        ObjectNode merged = merge(rows.get("card_control", id), req, "card_id", "card_program_id");
        ControlRules.validateControl(ControlSpecs.from(merged));
    }

    @Transactional
    public void controlChanged(UUID id) {
        events.publish("cards.card_control.changed", "card_control", rows.get("card_control", id));
    }

    @Transactional
    public void deleteControl(UUID id) {
        ObjectNode row = rows.lock("card_control", id);
        rows.execute("delete from card_control where id = ?", id);
        row.put("is_active", false);
        row.put("deleted", true);
        events.publish("cards.card_control.changed", "card_control", row);
    }

    // ------------------------------------------------------------------ limits

    @Transactional
    public JsonNode createLimit(JsonNode req) {
        validateScope(req);
        validateLimit(req);
        ObjectNode row = rows.insert("card_spend_limit", rows.columns("card_spend_limit", req));
        events.publish("cards.card_spend_limit.changed", "card_spend_limit", row);
        return row;
    }

    @Transactional(readOnly = true)
    public void checkLimitUpdate(UUID id, JsonNode req) {
        validateLimit(merge(rows.get("card_spend_limit", id), req, "card_id", "card_program_id"));
    }

    @Transactional
    public void limitChanged(UUID id) {
        events.publish("cards.card_spend_limit.changed", "card_spend_limit", rows.get("card_spend_limit", id));
    }

    @Transactional
    public void deleteLimit(UUID id) {
        ObjectNode row = rows.lock("card_spend_limit", id);
        rows.execute("delete from card_spend_limit where id = ?", id);
        row.put("is_active", false);
        row.put("deleted", true);
        events.publish("cards.card_spend_limit.changed", "card_spend_limit", row);
    }

    private static void validateLimit(JsonNode l) {
        ControlRules.validateLimit(Rows.str(l, "period"), Rows.str(l, "channel"), Rows.dec(l, "max_amount"),
                Rows.integer(l, "max_count"), Rows.str(l, "currency"));
    }

    private void validateScope(JsonNode req) {
        UUID card = Rows.uuid(req, "card_id");
        UUID program = Rows.uuid(req, "card_program_id");
        ControlRules.validateScope(card, program);
        if (card != null) {
            ObjectNode c = rows.find("card", card)
                    .orElseThrow(() -> ApiException.unprocessable("card_not_found", "card " + card + " not found"));
            if (List.of("CLOSED", "REPLACED").contains(Rows.str(c, "status"))) {
                throw ApiException.unprocessable("card_closed", "controls cannot be added to a closed card");
            }
        }
        if (program != null && rows.find("card_program", program).isEmpty()) {
            throw ApiException.unprocessable("program_not_found", "card program " + program + " not found");
        }
    }

    private static ObjectNode merge(ObjectNode current, JsonNode req, String... immutable) {
        for (String f : immutable) {
            if (req.hasNonNull(f) && !req.get(f).asString().equals(Rows.str(current, f))) {
                throw ApiException.unprocessable("field_not_updatable", f + " cannot be changed; delete and recreate");
            }
        }
        ObjectNode merged = current.deepCopy();
        req.propertyNames().forEach(n -> {
            if (!req.get(n).isNull()) {
                merged.set(n, req.get(n));
            }
        });
        return merged;
    }

    // ------------------------------------------------------------------ card views (card + program scope)

    /**
     * Rows of {@code table} that apply to the card: its own and its program's, newest first, keyset paged.
     * Returns the page JSON {data, has_more, next_cursor}.
     */
    @Transactional(readOnly = true)
    public ObjectNode forCard(String table, UUID cardId, Integer limit, String cursor) {
        ObjectNode card = rows.get("card", cardId);
        int n = Cursor.limit(limit);
        String sql = "select row_to_json(t)::text from " + table + " t where (t.card_id = ? or t.card_program_id = ?)"
                + (cursor == null || cursor.isBlank() ? "" : " and t.id < '" + Cursor.decode(cursor) + "'::uuid")
                + " order by t.id desc limit ?";
        List<ObjectNode> list = rows.query(sql, cardId, Rows.uuid(card, "card_program_id"), n + 1);
        ObjectNode page = rows.object();
        ArrayNode data = page.putArray("data");
        boolean more = list.size() > n;
        list.stream().limit(n).forEach(data::add);
        page.put("has_more", more);
        if (more) {
            page.put("next_cursor", Cursor.encode(Rows.uuid(list.get(n - 1), "id")));
        } else {
            page.putNull("next_cursor");
        }
        return page;
    }
}
