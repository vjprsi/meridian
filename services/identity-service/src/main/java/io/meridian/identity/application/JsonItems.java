package io.meridian.identity.application;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * List-valued {@code json[]} columns (actions, scopes, permission ids, incompatible role ids), stored as JSON arrays.
 * Reading also tolerates the legacy {@code {"items": [...]}} storage shape of rows written before the contract typed
 * these columns as arrays.
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
        } else if (n.isString()) {
            out.add(n.asString());
        }
        return out;
    }

    public static ArrayNode canonical(JsonMapper json, List<String> items) {
        ArrayNode a = json.createArrayNode();
        items.forEach(a::add);
        return a;
    }
}
