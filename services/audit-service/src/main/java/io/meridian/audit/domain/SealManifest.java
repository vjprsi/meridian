package io.meridian.audit.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manifest of one sealed daily batch, stored next to the events object on WORM storage. Its canonical JSON (sorted
 * keys, {@link CanonicalJson}) is hashed; the hash is kept in {@code audit_seal.manifest_sha256}, published in
 * {@code platform.audit_seal.created} and chained into the next day's manifest ({@code previous_manifest_sha256}), so
 * neither a storage object nor a seal row can be replaced without the mismatch showing.
 *
 * <p>The events object holds one line per event: {@link HashChain#canonical} of the event. The SHA-256 of a line is the
 * event's hash, and each line carries {@code prev_hash}, so the batch can be verified without the database.
 */
public record SealManifest(UUID tenantId, LocalDate sealDate, Long fromSequence, Long toSequence, int eventCount,
                           String firstPrevHash, String lastHash, String contentSha256, String contentKey,
                           String previousManifestSha256, LocalDate retainUntil, String objectLockMode,
                           OffsetDateTime sealedAt) {
    public static final int VERSION = 1;

    public String canonical() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", VERSION);
        m.put("tenant_id", tenantId.toString());
        m.put("seal_date", sealDate.toString());
        m.put("from_sequence", fromSequence);
        m.put("to_sequence", toSequence);
        m.put("event_count", eventCount);
        m.put("first_prev_hash", firstPrevHash);
        m.put("last_hash", lastHash);
        m.put("content_sha256", contentSha256);
        m.put("content_key", contentKey);
        m.put("content_format", "audit-canonical-v" + HashChain.VERSION + " (one line per event; line SHA-256 = event hash)");
        m.put("hash_algorithm", "SHA-256");
        m.put("previous_manifest_sha256", previousManifestSha256);
        m.put("retain_until", retainUntil.toString());
        m.put("object_lock_mode", objectLockMode);
        m.put("sealed_at", sealedAt.toString());
        return CanonicalJson.write(m);
    }
}
