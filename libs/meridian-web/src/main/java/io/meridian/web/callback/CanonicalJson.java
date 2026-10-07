package io.meridian.web.callback;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Canonical JSON for signatures over structured callbacks: object members sorted by name, null members omitted,
 * no whitespace, strings JSON-escaped, numbers and booleans as received.
 */
public final class CanonicalJson {
    private CanonicalJson() {}

    public static String of(JsonNode node) {
        StringBuilder out = new StringBuilder();
        write(node, out);
        return out.toString();
    }

    private static void write(JsonNode n, StringBuilder out) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            out.append("null");
        } else if (n.isObject()) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            for (var e : n.properties()) {
                if (e.getValue() != null && !e.getValue().isNull()) {
                    sorted.put(e.getKey(), e.getValue());
                }
            }
            List<String> parts = new ArrayList<>();
            sorted.forEach((k, v) -> {
                StringBuilder b = new StringBuilder();
                b.append(JsonNodeFactory.instance.stringNode(k).toString()).append(':');
                write(v, b);
                parts.add(b.toString());
            });
            out.append('{').append(String.join(",", parts)).append('}');
        } else if (n.isArray()) {
            out.append('[');
            for (int i = 0; i < n.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(n.get(i), out);
            }
            out.append(']');
        } else {
            out.append(n.toString());
        }
    }
}
