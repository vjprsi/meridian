package io.meridian.onboarding.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * List-valued JSON columns (channels, product_ids, step_codes) are typed as JSON objects by the contract, so a list
 * is accepted either as {@code {"values": [...]}} or as a set of flags {@code {"MOBILE_APP": true}} (or a raw array).
 */
public final class JsonValues {
    private JsonValues() {}

    public static List<String> values(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n == null || n.isNull() || n.isMissingNode()) {
            return out;
        }
        if (n.isArray()) {
            n.forEach(v -> out.add(v.asString()));
        } else if (n.isObject()) {
            JsonNode vals = n.get("values");
            if (vals != null && vals.isArray()) {
                vals.forEach(v -> out.add(v.asString()));
            } else {
                for (Map.Entry<String, JsonNode> e : n.properties()) {
                    JsonNode v = e.getValue();
                    if (v != null && !v.isNull() && !(v.isBoolean() && !v.asBoolean())) {
                        out.add(e.getKey());
                    }
                }
            }
        } else if (n.isString()) {
            out.add(n.asString());
        }
        return out;
    }

    /** The canonical object form stored in json columns: {@code {"values": [...]}}. */
    public static ObjectNode of(List<String> values) {
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        values.forEach(o.putArray("values")::add);
        return o;
    }

    public static boolean blank(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode() || (n.isString() && n.asString().isBlank());
    }
}
