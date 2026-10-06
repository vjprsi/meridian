package io.meridian.archive.infrastructure;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Reads list-valued json columns. The generated contract types json columns as objects, so lists such as
 * {@code cycle_types} or {@code depends_on} are accepted as an array, as an object holding one array
 * (canonical form {@code {"codes": [...]}}), or as an object whose keys are the values ({@code {"EOD": true}}).
 */
public final class JsonValues {
    private JsonValues() {}

    public static List<String> strings(JsonNode node) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (node == null || node.isNull() || node.isMissingNode()) {
            return List.of();
        }
        if (node.isArray()) {
            node.forEach(v -> addScalar(out, v));
        } else if (node.isObject()) {
            boolean hadArray = false;
            for (String name : node.propertyNames()) {
                JsonNode v = node.get(name);
                if (v.isArray()) {
                    hadArray = true;
                    v.forEach(e -> addScalar(out, e));
                }
            }
            if (!hadArray) {
                for (String name : node.propertyNames()) {
                    JsonNode v = node.get(name);
                    if (!v.isNull() && !(v.isBoolean() && !v.asBoolean())) {
                        out.add(name);
                    }
                }
            }
        } else {
            addScalar(out, node);
        }
        return new ArrayList<>(out);
    }

    private static void addScalar(LinkedHashSet<String> out, JsonNode v) {
        if (v != null && !v.isNull() && v.isValueNode()) {
            String s = v.asString().trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
    }

    public static String text(JsonNode n, String field) {
        return n != null && n.hasNonNull(field) ? n.get(field).asString() : null;
    }
}
