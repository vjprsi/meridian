package io.meridian.onboarding.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

/** Helpers for list-valued JSON columns (channels, product_ids, step_codes), which the contract types as arrays. */
public final class JsonValues {
    private JsonValues() {}

    /** The string values of a JSON array (empty for null / missing). */
    public static List<String> values(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n != null && n.isArray()) {
            n.forEach(v -> out.add(v.asString()));
        }
        return out;
    }

    public static ArrayNode of(Collection<String> values) {
        ArrayNode a = JsonNodeFactory.instance.arrayNode();
        values.forEach(a::add);
        return a;
    }

    public static boolean blank(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode() || (n.isString() && n.asString().isBlank());
    }
}
