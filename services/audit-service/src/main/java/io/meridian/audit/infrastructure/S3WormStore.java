package io.meridian.audit.infrastructure;

import io.meridian.audit.domain.SigV4;
import io.meridian.common.error.ApiException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Sealed audit batches on S3-compatible storage with Object Lock. Each PUT carries {@code x-amz-object-lock-mode}
 * (COMPLIANCE in production: nobody, not even the root account, can shorten the retention or delete the version) and
 * {@code x-amz-object-lock-retain-until-date}, plus {@code Content-MD5} as S3 requires for locked writes; requests are
 * signed with SigV4 in the Authorization header. The bucket must be created with Object Lock enabled
 * ({@code infra/tofu} module {@code cell}, bucket {@code audit}). Mode NONE (local stacks without Object Lock) writes
 * plain objects; tamper detection then relies on the manifest hashes alone.
 */
public class S3WormStore implements WormStore {
    static final Set<String> MODES = Set.of("COMPLIANCE", "GOVERNANCE", "NONE");

    private final String endpoint;
    private final String bucket;
    private final SigV4 signer;
    private final String lockMode;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public S3WormStore(String endpoint, String bucket, String region, String accessKey, String secretKey, String lockMode) {
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.bucket = bucket;
        this.signer = new SigV4(accessKey, secretKey, region);
        this.lockMode = lockMode == null ? "COMPLIANCE" : lockMode.trim().toUpperCase(Locale.ROOT);
        if (!MODES.contains(this.lockMode)) {
            throw new IllegalStateException("meridian.audit.worm.object-lock-mode must be one of " + MODES);
        }
    }

    @Override
    public String lockMode() {
        return lockMode;
    }

    @Override
    public String put(String key, byte[] content, String contentType, LocalDate retainUntil) {
        String path = "/" + bucket + "/" + key;
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("host", host());
        headers.put("content-type", contentType);
        headers.put("content-md5", Base64.getEncoder().encodeToString(md5(content)));
        if (!"NONE".equals(lockMode)) {
            headers.put("x-amz-object-lock-mode", lockMode);
            headers.put("x-amz-object-lock-retain-until-date", retainUntil.atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toString());
        }
        Map<String, String> signed = signer.sign("PUT", path, headers, SigV4.sha256Hex(content), Instant.now());
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(endpoint + path)).timeout(Duration.ofSeconds(60))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(content));
        signed.forEach((k, v) -> {
            if (!"host".equals(k)) {
                b.header(k, v);
            }
        });
        HttpResponse<String> r = send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() >= 300) {
            throw new ApiException(502, "worm_store_error", "Object store refused " + key + ": HTTP " + r.statusCode()
                    + " " + abbreviate(r.body()));
        }
        return "s3://" + bucket + "/" + key;
    }

    @Override
    public byte[] get(String uri) {
        String prefix = "s3://" + bucket + "/";
        if (uri == null || !uri.startsWith(prefix)) {
            throw new ApiException(409, "storage_location_unknown", "Object " + uri + " is not in the configured bucket");
        }
        String path = "/" + bucket + "/" + uri.substring(prefix.length());
        Map<String, String> signed = signer.sign("GET", path, Map.of("host", host()), SigV4.EMPTY_SHA256, Instant.now());
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(endpoint + path)).timeout(Duration.ofSeconds(60)).GET();
        signed.forEach((k, v) -> {
            if (!"host".equals(k)) {
                b.header(k, v);
            }
        });
        HttpResponse<byte[]> r = send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() == 404) {
            throw new ApiException(410, "object_missing", "Sealed object " + uri + " is missing from storage");
        }
        if (r.statusCode() >= 300) {
            throw new ApiException(502, "worm_store_error", "Object store answered HTTP " + r.statusCode() + " for " + uri);
        }
        return r.body();
    }

    private String host() {
        URI u = URI.create(endpoint);
        return u.getPort() < 0 ? u.getHost() : u.getHost() + ":" + u.getPort();
    }

    private <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> handler) {
        try {
            return http.send(req, handler);
        } catch (IOException e) {
            throw new ApiException(502, "worm_store_unreachable", "Object store not reachable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(502, "worm_store_unreachable", "Interrupted while calling the object store");
        }
    }

    private static byte[] md5(byte[] content) {
        try {
            return MessageDigest.getInstance("MD5").digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) : s;
    }
}
