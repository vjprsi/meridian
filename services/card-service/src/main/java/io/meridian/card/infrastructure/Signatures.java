package io.meridian.card.infrastructure;

import io.meridian.common.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC-SHA256 signatures of provider callbacks (hex in X-Signature). */
public final class Signatures {
    private Signatures() {}

    public static String sign(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 401 invalid_signature unless {@code signature} is the HMAC of {@code body} (constant-time compare). */
    public static void verify(String secret, byte[] body, String signature) {
        if (signature == null || signature.isBlank() || body == null || body.length == 0) {
            throw new ApiException(401, "invalid_signature", "X-Signature is missing or the body is empty");
        }
        byte[] expected = sign(secret, body).getBytes(StandardCharsets.US_ASCII);
        byte[] given = signature.trim().toLowerCase().getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, given)) {
            throw new ApiException(401, "invalid_signature", "X-Signature does not match the request body");
        }
    }
}
