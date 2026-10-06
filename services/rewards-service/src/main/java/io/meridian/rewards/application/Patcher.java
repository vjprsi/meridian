package io.meridian.rewards.application;

import io.meridian.rewards.infrastructure.Rows;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

/** Optimistic PATCH with business validation of the merged row (current values overlaid with the changes). */
@Component
public class Patcher {
    private final Rows rows;
    private final CrudEngine crud;

    public Patcher(Rows rows, CrudEngine crud) {
        this.rows = rows;
        this.crud = crud;
    }

    /** validator receives (current row, merged row) and throws to reject. */
    public ObjectNode patch(String table, UUID id, Object body, String ifMatch, BiConsumer<ObjectNode, ObjectNode> validator) {
        int expected = Json.parseVersion(ifMatch);
        ObjectNode current = rows.lock(table, id);
        ObjectNode changes = crud.toColumns(table, body);
        changes.remove("id");
        if (changes.isEmpty()) {
            return current;
        }
        if (current.path("row_version").asInt() != expected) {
            throw Json.versionConflict(expected);
        }
        ObjectNode merged = current.deepCopy();
        merged.setAll(changes);
        validator.accept(current, merged);
        return rows.update(table, id, changes, expected).orElseThrow(() -> Json.versionConflict(expected));
    }

    public ObjectNode columns(String table, Object body) {
        return crud.toColumns(table, body);
    }
}
