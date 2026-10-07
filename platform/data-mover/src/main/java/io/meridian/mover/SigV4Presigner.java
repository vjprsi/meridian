package io.meridian.mover;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * (Copy of document-service's presigner.) AWS Signature Version 4 query-string presigning for S3-compatible object stores (AWS S3, SeaweedFS, MinIO).
 * Only the {@code host} header is signed and the payload is {@code UNSIGNED-PAYLOAD}, as for browser uploads.
 */
public final class SigV4Presigner {
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final HexFormat HEX = HexFormat.of();

    private final String accessKey;
    private final String secretKey;
    private final String region;
    private final String service;

    public SigV4Presigner(String accessKey, String secretKey, String region) {
        this(accessKey, secretKey, region, "s3");
    }

    public SigV4Presigner(String accessKey, String secretKey, String region, String service) {
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.region = region;
        this.service = service;
    }

    /**
     * @param method        HTTP method (GET, PUT)
     * @param baseUrl       scheme://host[:port] without trailing slash
     * @param path          absolute object path, unencoded (e.g. /bucket/tenant/id/file.pdf)
     * @param extraQuery    additional query parameters to sign (e.g. response-content-disposition)
     * @param expiresSeconds 1..604800
     */
    public String presign(String method, String baseUrl, String path, Map<String, String> extraQuery,
                          long expiresSeconds, Instant now) {
        if (expiresSeconds < 1 || expiresSeconds > 604_800) {
            throw new IllegalArgumentException("expires must be between 1 second and 7 days");
        }
        String host = baseUrl.replaceFirst("^https?://", "");
        int slash = host.indexOf('/');
        if (slash >= 0) {
            host = host.substring(0, slash);
        }
        host = stripDefaultPort(baseUrl, host);
        String amzDate = AMZ_DATE.format(now);
        String date = amzDate.substring(0, 8);
        String scope = date + "/" + region + "/" + service + "/aws4_request";

        TreeMap<String, String> query = new TreeMap<>();
        if (extraQuery != null) {
            query.putAll(extraQuery);
        }
        query.put("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
        query.put("X-Amz-Credential", accessKey + "/" + scope);
        query.put("X-Amz-Date", amzDate);
        query.put("X-Amz-Expires", Long.toString(expiresSeconds));
        query.put("X-Amz-SignedHeaders", "host");

        StringBuilder canonicalQuery = new StringBuilder();
        query.forEach((k, v) -> {
            if (!canonicalQuery.isEmpty()) {
                canonicalQuery.append('&');
            }
            canonicalQuery.append(uriEncode(k, true)).append('=').append(uriEncode(v, true));
        });
        String canonicalUri = uriEncode(path, false);
        String canonicalRequest = method + "\n" + canonicalUri + "\n" + canonicalQuery + "\n"
                + "host:" + host + "\n\nhost\nUNSIGNED-PAYLOAD";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + HEX.formatHex(sha256(canonicalRequest));
        byte[] kDate = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        byte[] kRegion = hmac(kDate, region);
        byte[] kService = hmac(kRegion, service);
        byte[] kSigning = hmac(kService, "aws4_request");
        String signature = HEX.formatHex(hmac(kSigning, stringToSign));
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + canonicalUri + "?" + canonicalQuery + "&X-Amz-Signature=" + signature;
    }

    private static String stripDefaultPort(String baseUrl, String host) {
        if (baseUrl.startsWith("https://") && host.endsWith(":443")) {
            return host.substring(0, host.length() - 4);
        }
        if (baseUrl.startsWith("http://") && host.endsWith(":80")) {
            return host.substring(0, host.length() - 3);
        }
        return host;
    }

    /** RFC 3986 encoding as required by SigV4 (unreserved characters kept; '/' kept in paths). */
    public static String uriEncode(String s, boolean encodeSlash) {
        StringBuilder b = new StringBuilder();
        for (byte by : s.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (by & 0xff);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~' || (c == '/' && !encodeSlash)) {
                b.append(c);
            } else {
                b.append('%').append(String.format("%02X", by & 0xff));
            }
        }
        return b.toString();
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
