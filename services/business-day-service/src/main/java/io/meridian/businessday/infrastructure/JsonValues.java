package io.meridian.businessday.infrastructure;

import io.meridian.common.error.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Reads JSON column values. List columns ({@code json[]} in the spec) are JSON arrays. */
public final class JsonValues {
    private JsonValues() {}

    /** Distinct, trimmed string values of a JSON array column; empty for null / missing. */
    public static List<String> strings(JsonNode node) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (node == null || node.isNull() || node.isMissingNode()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw ApiException.unprocessable("invalid_list", "Expected a JSON array, got " + node.getNodeType());
        }
        node.forEach(v -> {
            if (v != null && !v.isNull() && v.isValueNode()) {
                String s = v.asString().trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        });
        return new ArrayList<>(out);
    }

    public static String text(JsonNode n, String field) {
        return n != null && n.hasNonNull(field) ? n.get(field).asString() : null;
    }
}
