package io.meridian.config.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** JSON helpers for configuration documents: RFC 6902-style diffs, canonical form and checksums. */
public final class JsonDiff {
    private JsonDiff() {}

    /**
     * Diff from {@code before} to {@code after} as a list of {@code {op, path, value}} operations (add, remove,
     * replace) with JSON Pointer paths. Objects are compared key by key; arrays and scalars are replaced whole.
     */
    public static ArrayNode diff(JsonMapper json, JsonNode before, JsonNode after) {
        ArrayNode ops = json.createArrayNode();
        walk(json, before, after, "", ops);
        return ops;
    }

    private static void walk(JsonMapper json, JsonNode a, JsonNode b, String path, ArrayNode ops) {
        boolean aMissing = a == null || a.isMissingNode();
        boolean bMissing = b == null || b.isMissingNode();
        if (aMissing && bMissing) {
            return;
        }
        if (aMissing) {
            ops.add(op(json, "add", path, b));
            return;
        }
        if (bMissing) {
            ops.add(op(json, "remove", path, null));
            return;
        }
        if (a.isObject() && b.isObject()) {
            TreeSet<String> keys = new TreeSet<>(a.propertyNames());
            keys.addAll(b.propertyNames());
            for (String k : keys) {
                walk(json, a.get(k), b.get(k), path + "/" + k.replace("~", "~0").replace("/", "~1"), ops);
            }
            return;
        }
        if (!a.equals(b)) {
            ops.add(op(json, "replace", path.isEmpty() ? "" : path, b));
        }
    }

    private static ObjectNode op(JsonMapper json, String op, String path, JsonNode value) {
        ObjectNode o = json.createObjectNode();
        o.put("op", op);
        o.put("path", path.isEmpty() ? "/" : path);
        if (value != null) {
            o.set("value", value);
        }
        return o;
    }

    /** Recursively sorts object keys so that equal documents serialise identically. */
    public static JsonNode canonical(JsonMapper json, JsonNode n) {
        if (n == null) {
            return null;
        }
        if (n.isObject()) {
            ObjectNode o = json.createObjectNode();
            List<String> keys = new ArrayList<>(n.propertyNames());
            keys.sort(String::compareTo);
            for (String k : keys) {
                o.set(k, canonical(json, n.get(k)));
            }
            return o;
        }
        if (n.isArray()) {
            ArrayNode arr = json.createArrayNode();
            for (JsonNode e : n) {
                arr.add(canonical(json, e));
            }
            return arr;
        }
        return n;
    }

    /** SHA-256 (hex) over the canonical serialisation. */
    public static String checksum(JsonMapper json, JsonNode n) {
        try {
            byte[] bytes = json.writeValueAsString(canonical(json, n)).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Structural equality ignoring key order and numeric representation (1 == 1.0). */
    public static boolean same(JsonMapper json, JsonNode a, JsonNode b) {
        if (a == null || b == null) {
            return a == b;
        }
        return diff(json, a, b).isEmpty() || numericallyEqual(a, b);
    }

    private static boolean numericallyEqual(JsonNode a, JsonNode b) {
        return a.isNumber() && b.isNumber() && a.decimalValue().compareTo(b.decimalValue()) == 0;
    }

    /** Reads {@code obj[key]} as an object, or null. */
    public static JsonNode objectAt(JsonNode obj, String... path) {
        JsonNode cur = obj;
        for (String p : path) {
            if (cur == null || !cur.isObject()) {
                return null;
            }
            cur = cur.get(p);
        }
        return cur != null && cur.isObject() ? cur : null;
    }
}
