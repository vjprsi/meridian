package io.meridian.audit.domain;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * AWS Signature Version 4 for S3 requests signed in the {@code Authorization} header (needed for Object Lock
 * headers, which presigned URLs cannot carry unsigned). Path-style addressing; no query string.
 */
public final class SigV4 {
    public static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final String accessKey;
    private final String secretKey;
    private final String region;

    public SigV4(String accessKey, String secretKey, String region) {
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.region = region;
    }

    /**
     * Signs a request: {@code headers} must contain {@code host}; {@code x-amz-date} and {@code x-amz-content-sha256}
     * are added. Returns every header to send (lower-case names) including {@code authorization}.
     */
    public Map<String, String> sign(String method, String canonicalPath, Map<String, String> headers, String payloadSha256,
                                    Instant now) {
        TreeMap<String, String> h = new TreeMap<>();
        headers.forEach((k, v) -> h.put(k.toLowerCase(Locale.ROOT), v.trim()));
        String amzDate = AMZ_DATE.format(now);
        String date = amzDate.substring(0, 8);
        h.put("x-amz-date", amzDate);
        h.put("x-amz-content-sha256", payloadSha256);
        StringBuilder canonicalHeaders = new StringBuilder();
        h.forEach((k, v) -> canonicalHeaders.append(k).append(':').append(v).append('\n'));
        String signedHeaders = String.join(";", h.keySet());
        String canonicalRequest = method + "\n" + encodePath(canonicalPath) + "\n\n" + canonicalHeaders + "\n" + signedHeaders
                + "\n" + payloadSha256;
        String scope = date + "/" + region + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        byte[] key = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        key = hmac(key, region);
        key = hmac(key, "s3");
        key = hmac(key, "aws4_request");
        String signature = HexFormat.of().formatHex(hmac(key, stringToSign));
        TreeMap<String, String> out = new TreeMap<>(h);
        out.put("authorization", "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature);
        return out;
    }

    /** URI-encodes each path segment (RFC 3986 unreserved characters kept), keeping the slashes. */
    static String encodePath(String path) {
        StringBuilder sb = new StringBuilder();
        for (byte b : path.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_'
                    || c == '.' || c == '~' || c == '/') {
                sb.append(c);
            } else {
                sb.append('%').append(String.format("%02X", b & 0xff));
            }
        }
        return sb.toString();
    }

    public static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
