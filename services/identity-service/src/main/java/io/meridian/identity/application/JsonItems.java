package io.meridian.identity.application;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * List-valued {@code json} columns (actions, scopes, permission ids, incompatible role ids). The generated contract
 * types every {@code json} column as a JSON object, so lists travel as {@code {"items": [...]}}; a bare array or an
 * object whose keys are the entries ({@code {"payments:read": true}}) is accepted too. Stored canonically as
 * {@code {"items": [...]}} so stored rows always deserialize into the generated models.
 */
public final class JsonItems {
    private JsonItems() {}

    public static List<String> of(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n == null || n.isNull() || n.isMissingNode()) {
            return out;
        }
        if (n.isArray()) {
            n.forEach(e -> out.add(e.asString()));
        } else if (n.isObject() && n.has("items") && n.get("items").isArray()) {
            n.get("items").forEach(e -> out.add(e.asString()));
        } else if (n.isObject()) {
            n.properties().forEach(e -> {
                if (!e.getValue().isNull() && !(e.getValue().isBoolean() && !e.getValue().asBoolean())) {
                    out.add(e.getKey());
                }
            });
        } else if (n.isString()) {
            out.add(n.asString());
        }
        return out;
    }

    public static ObjectNode canonical(JsonMapper json, List<String> items) {
        ObjectNode o = json.createObjectNode();
        ArrayNode a = o.putArray("items");
        items.forEach(a::add);
        return o;
    }
}
