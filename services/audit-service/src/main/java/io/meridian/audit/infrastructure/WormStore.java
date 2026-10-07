package io.meridian.audit.infrastructure;

import java.time.LocalDate;

/** Write-once object storage for sealed audit batches (S3 Object Lock in production). */
public interface WormStore {
    /** Stores an object that cannot be changed or deleted before {@code retainUntil}; returns its URI. */
    String put(String key, byte[] content, String contentType, LocalDate retainUntil);

    /** Reads an object back by the URI {@link #put} returned. */
    byte[] get(String uri);

    /** Object lock mode applied to new objects: COMPLIANCE, GOVERNANCE or NONE. */
    String lockMode();
}
