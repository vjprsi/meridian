package io.meridian.search.api;

import io.meridian.common.error.ApiException;
import io.meridian.search.infrastructure.Rows;
import io.meridian.web.crud.CrudEngine;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Validated PATCH: lock the row, merge the changes, run the rule on the merged values, apply with If-Match. */
@Component
class Updates {
    private final CrudEngine crud;
    private final Rows rows;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    Updates(CrudEngine crud, Rows rows, TransactionTemplate tx, JsonMapper json) {
        this.crud = crud;
        this.rows = rows;
        this.tx = tx;
        this.json = json;
    }

    /**
     * @param rule receives (merged values, changes); may add derived columns to {@code changes}
     */
    <T> ResponseEntity<T> apply(String table, UUID id, String ifMatch, ObjectNode changes, Class<T> type,
                                BiConsumer<ObjectNode, ObjectNode> rule) {
        int version = Http.ifMatch(ifMatch);
        return tx.execute(s -> {
            JsonNode current = rows.require(table, id, true);
            ObjectNode merged = ((ObjectNode) current).deepCopy();
            merged.setAll(changes);
            rule.accept(merged, changes);
            String row = crud.updateRow(table, Map.of("id", id), changes, version)
                    .orElseThrow(() -> ApiException.conflict("version_conflict",
                            "If-Match " + version + " does not match the current version; reload and retry"));
            return ResponseEntity.ok().eTag("\"" + json.readTree(row).get("row_version").asString() + "\"")
                    .body(crud.read(row, type));
        });
    }
}
