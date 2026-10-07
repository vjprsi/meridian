package io.meridian.filegateway.infrastructure;

import io.meridian.common.error.ApiException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Object store for raw and generated files. Objects are written once (create-new) under
 * {@code <tenant>/<direction>/<file id>/<name>} and addressed as {@code store://<key>}. Download links are
 * pre-signed: an HMAC-SHA256 over key and expiry, verified by the blob endpoint. The local-disk backend
 * ({@code meridian.files.storage-dir}) stands in for S3/Blob storage with the same contract.
 */
@Component
public class FileStore {
    public static final String SCHEME = "store://";
    private static final String QUARANTINE = "quarantine";

    private final Path root;
    private final byte[] signingKey;
    private final Duration urlTtl;

    public FileStore(@Value("${meridian.files.storage-dir:${java.io.tmpdir}/meridian-file-gateway}") String dir,
                     @Value("${meridian.files.signing-key:local-dev-signing-key-change-me}") String signingKey,
                     @Value("${meridian.files.url-ttl:PT15M}") Duration urlTtl) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
        this.signingKey = signingKey.getBytes(StandardCharsets.UTF_8);
        this.urlTtl = urlTtl;
    }

    public String put(UUID tenant, String direction, UUID fileId, String fileName, byte[] content) {
        return write(tenant + "/" + direction + "/" + fileId + "/" + safeName(fileName), content);
    }

    /**
     * Stores content that failed virus scanning in the quarantine area, obfuscated (as antivirus vaults do) so
     * that host-level scanners do not delete the evidence and the bytes can never be executed or served as is.
     */
    public String putQuarantined(UUID tenant, UUID fileId, String fileName, byte[] content) {
        return write(tenant + "/" + QUARANTINE + "/" + fileId + "/" + safeName(fileName) + ".q", xor(content));
    }

    private String write(String key, byte[] content) {
        Path p = resolve(key);
        try {
            Files.createDirectories(p.getParent());
            Files.write(p, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (java.nio.file.FileAlreadyExistsException e) {
            throw ApiException.conflict("object_exists", "Object " + key + " already exists (write-once storage)");
        } catch (IOException e) {
            throw new UncheckedIOException("cannot store " + key, e);
        }
        return SCHEME + key;
    }

    public byte[] get(String storageUri) {
        String key = key(storageUri);
        Path p = resolve(key);
        try {
            byte[] data = Files.readAllBytes(p);
            return isQuarantined(key) ? xor(data) : data;
        } catch (IOException e) {
            throw new ApiException(410, "object_missing", "Stored object " + storageUri + " is not available");
        }
    }

    public record SignedUrl(String path, Instant expiresAt) {}

    /** Path (relative to the service base URL) of a pre-signed, time-limited download link. */
    public SignedUrl sign(String storageUri) {
        String key = key(storageUri);
        if (isQuarantined(key)) {
            throw ApiException.conflict("file_quarantined", "Quarantined content is never served");
        }
        Instant exp = Instant.now().plus(urlTtl);
        String sig = hmac(key + "\n" + exp.getEpochSecond());
        return new SignedUrl("/v1/files/blobs?key=" + java.net.URLEncoder.encode(key, StandardCharsets.UTF_8)
                + "&expires=" + exp.getEpochSecond() + "&signature=" + sig, exp);
    }

    public byte[] readSigned(String key, long expires, String signature) {
        long now = Instant.now().getEpochSecond();
        if (now > expires) {
            throw new ApiException(403, "url_expired", "The download link has expired");
        }
        // SEC-36: a link is never valid for longer than this service issues them (+1 min clock skew)
        if (expires > now + urlTtl.toSeconds() + 60) {
            throw new ApiException(403, "invalid_expiry", "The download link expiry exceeds the maximum lifetime");
        }
        if (key == null || isQuarantined(key)) {
            throw new ApiException(403, "file_quarantined", "Quarantined content is never served");
        }
        if (signature == null || !MessageDigest.isEqual(hmac(key + "\n" + expires).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(403, "invalid_signature", "The download link signature is invalid");
        }
        return get(SCHEME + key);
    }

    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private String hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String key(String storageUri) {
        if (storageUri == null || !storageUri.startsWith(SCHEME)) {
            throw new ApiException(410, "object_missing", "No stored object for " + storageUri);
        }
        return storageUri.substring(SCHEME.length());
    }

    private Path resolve(String key) {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) {
            throw ApiException.badRequest("invalid_key", "Invalid object key");
        }
        return p;
    }

    private static boolean isQuarantined(String key) {
        String[] parts = key.split("/", 3);
        return parts.length > 1 && QUARANTINE.equals(parts[1]);
    }

    private static byte[] xor(byte[] in) {
        byte[] out = new byte[in.length];
        for (int i = 0; i < in.length; i++) {
            out[i] = (byte) (in[i] ^ 0x5A);
        }
        return out;
    }

    static String safeName(String name) {
        String n = name == null ? "file" : name.replaceAll("[^A-Za-z0-9._-]", "_");
        return n.isBlank() || n.startsWith(".") ? "file" + n : n;
    }
}
