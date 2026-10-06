package io.meridian.onboarding.api;

import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Runs POST actions exactly once per Idempotency-Key and maps row JSON to API models. */
@Component
class ActionRunner {
    private final Idempotency idempotency;
    private final CrudEngine crud;
    private final JsonMapper json;

    ActionRunner(Idempotency idempotency, CrudEngine crud, JsonMapper json) {
        this.idempotency = idempotency;
        this.crud = crud;
        this.json = json;
    }

    /** The stored request fingerprint covers the operation, the path ids and the body. */
    <T> ResponseEntity<T> run(String key, String op, Object id, Object body, Class<T> type, HttpStatus status,
                              Supplier<ObjectNode> action) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("op", op);
        request.put("id", String.valueOf(id));
        request.put("body", body);
        return idempotency.execute(key, request, type, () -> {
            ObjectNode row = action.get();
            return ResponseEntity.status(status).body(model(row, type));
        });
    }

    <T> T model(ObjectNode row, Class<T> type) {
        return crud.read(json.writeValueAsString(row), type);
    }

    <T> ResponseEntity<T> withEtag(ObjectNode row, Class<T> type) {
        return ResponseEntity.ok().eTag("\"" + row.get("row_version").asString() + "\"").body(model(row, type));
    }

    ObjectNode tree(Object body) {
        return body == null ? json.createObjectNode() : (ObjectNode) json.valueToTree(body);
    }
}
