package io.meridian.config.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.events.OutboxWriter;
import io.meridian.web.crud.CrudEngine;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Create / update / delete of a row with business validation and a domain event in the same transaction
 * (callers are {@code @Transactional}). Validation receives the merged state (existing row + changes).
 */
@Component
public class EventedCrud {
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final JsonMapper json;

    public EventedCrud(CrudEngine crud, OutboxWriter outbox, JsonMapper json) {
        this.crud = crud;
        this.outbox = outbox;
        this.json = json;
    }

    public String create(String table, Object body, Consumer<ObjectNode> validate, String event) {
        ObjectNode values = crud.toColumns(table, body);
        validate.accept(values);
        values.put("id", Ids.newId().toString());
        String row = crud.insert(table, values);
        outbox.publish(event, table, UUID.fromString(values.get("id").asString()), json.readTree(row));
        return row;
    }

    public String update(String table, UUID id, Object body, Integer expectedVersion, Consumer<ObjectNode> validate,
                         String event) {
        String current = crud.findOne(table, Map.of("id", id)).orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
        ObjectNode changes = crud.toColumns(table, body);
        ObjectNode merged = (ObjectNode) json.readTree(current);
        changes.properties().forEach(e -> merged.set(e.getKey(), e.getValue()));
        validate.accept(merged);
        if (changes.isEmpty()) {
            return current;
        }
        String row = crud.updateRow(table, Map.of("id", id), changes, expectedVersion)
                .orElseThrow(() -> ApiException.conflict("version_conflict",
                        "If-Match " + expectedVersion + " does not match the current version; reload and retry"));
        outbox.publish(event, table, id, json.readTree(row));
        return row;
    }

    public void delete(String table, UUID id, Consumer<JsonNode> beforeDelete, String event) {
        String current = crud.findOne(table, Map.of("id", id)).orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
        JsonNode node = json.readTree(current);
        beforeDelete.accept(node);
        crud.delete(table, io.meridian.web.crud.Crud.params().with("id", id));
        ObjectNode payload = (ObjectNode) node;
        payload.put("deleted", true);
        outbox.publish(event, table, id, payload);
    }

    public static int parseIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw ApiException.badRequest("if_match_required", "If-Match header with the current row_version is required");
        }
        try {
            return Integer.parseInt(ifMatch.replace("W/", "").replace("\"", "").trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("invalid_if_match", "If-Match must be the resource's row_version");
        }
    }
}
