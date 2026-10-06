package io.meridian.archive.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Certificate of destruction: canonical JSON (keys sorted recursively, no insignificant whitespace) and its
 * SHA-256, so the stored hash can be re-verified independently of serialisation details.
 */
public final class Certificate {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Certificate() {}

    public static String canonical(JsonNode node) {
        return JSON.writeValueAsString(sort(node));
    }

    public static String hash(JsonNode certificate) {
        return sha256(canonical(certificate).getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode sort(JsonNode n) {
        if (n.isObject()) {
            TreeMap<String, JsonNode> m = new TreeMap<>();
            n.propertyNames().forEach(k -> m.put(k, sort(n.get(k))));
            ObjectNode o = JSON.createObjectNode();
            m.forEach(o::set);
            return o;
        }
        if (n.isArray()) {
            ArrayNode a = JSON.createArrayNode();
            n.forEach(c -> a.add(sort(c)));
            return a;
        }
        return n;
    }
}
