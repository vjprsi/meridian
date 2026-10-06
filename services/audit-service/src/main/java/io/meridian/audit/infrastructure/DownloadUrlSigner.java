package io.meridian.audit.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Signs time-limited download URLs for export files (HMAC-SHA256 over tenant, export, document and expiry).
 * Placeholder for the document-service / object-store pre-signed URL; the signing key must come from the vault
 * outside local development.
 */
@Component
public class DownloadUrlSigner {
    private final byte[] key;
    private final String baseUrl;

    public DownloadUrlSigner(
            @Value("${meridian.audit.download-signing-key:dev-only-audit-download-signing-key}") String key,
            @Value("${meridian.audit.download-base-url:https://files.meridian.example/exports}") String baseUrl) {
        this.key = key.getBytes(StandardCharsets.UTF_8);
        this.baseUrl = baseUrl;
    }

    public String sign(UUID tenantId, UUID exportId, UUID documentId, Instant expiresAt) {
        long exp = expiresAt.getEpochSecond();
        String message = tenantId + "|" + exportId + "|" + documentId + "|" + exp;
        return baseUrl + "/" + documentId + "?export=" + exportId + "&expires=" + exp + "&signature=" + hmac(message);
    }

    private String hmac(String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
