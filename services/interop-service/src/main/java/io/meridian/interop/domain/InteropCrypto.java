package io.meridian.interop.domain;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Interledger-style crypto-conditions and callback signatures.
 * <ul>
 *   <li>fulfilment: 32 random bytes, base64url without padding (the payee FSP's secret preimage)</li>
 *   <li>condition: base64url(SHA-256(fulfilment bytes)) - shared in the quote, proven by the fulfilment on commit</li>
 *   <li>signature: lowercase hex HMAC-SHA256(secret, canonical JSON) where canonical JSON sorts object keys
 *       recursively and has no whitespace</li>
 * </ul>
 */
public final class InteropCrypto {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64URL_DEC = Base64.getUrlDecoder();
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private InteropCrypto() {}

    public static String newFulfilment() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return B64URL.encodeToString(b);
    }

    /** Deterministic fulfilment derived from a secret and a seed (used by the built-in switch simulator). */
    public static String fulfilmentFrom(String secret, String seed) {
        return B64URL.encodeToString(hmac(secret, seed));
    }

    /** Condition of a fulfilment; throws IllegalArgumentException for a malformed (non base64url / not 32 bytes) one. */
    public static String condition(String fulfilment) {
        byte[] preimage = decode(fulfilment);
        return B64URL.encodeToString(sha256(preimage));
    }

    /** Constant-time check that the fulfilment hashes to the condition; false for malformed input. */
    public static boolean verify(String fulfilment, String condition) {
        if (fulfilment == null || condition == null) {
            return false;
        }
        try {
            byte[] expected = B64URL_DEC.decode(condition);
            return MessageDigest.isEqual(expected, sha256(decode(fulfilment)));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] decode(String fulfilment) {
        byte[] b = B64URL_DEC.decode(fulfilment);
        if (b.length != 32) {
            throw new IllegalArgumentException("a fulfilment is 32 bytes");
        }
        return b;
    }

    /** Canonical JSON: object keys sorted recursively, no insignificant whitespace. */
    public static String canonicalJson(JsonNode node) {
        return MAPPER.writeValueAsString(sort(node));
    }

    private static JsonNode sort(JsonNode node) {
        if (node == null) {
            return MAPPER.nullNode();
        }
        if (node.isObject()) {
            ObjectNode out = MAPPER.createObjectNode();
            List<String> names = new ArrayList<>(node.propertyNames());
            names.sort(null);
            for (String n : names) {
                out.set(n, sort(node.get(n)));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = MAPPER.createArrayNode();
            node.forEach(e -> out.add(sort(e)));
            return out;
        }
        return node;
    }

    public static String sign(String secret, JsonNode body) {
        return HexFormat.of().formatHex(hmac(secret, canonicalJson(body)));
    }

    /** Constant-time signature check; false when the secret or the signature is missing or malformed. */
    public static boolean verifySignature(String secret, JsonNode body, String signature) {
        if (secret == null || secret.isBlank() || signature == null || signature.isBlank()) {
            return false;
        }
        byte[] given;
        try {
            given = HexFormat.of().parseHex(signature.trim().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(hmac(secret, canonicalJson(body)), given);
    }

    private static byte[] hmac(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] sha256(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
