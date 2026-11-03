package io.meridian.common.id;

import com.github.f4b6a3.uuid.UuidCreator;
import java.util.UUID;

/** Time-ordered UUIDv7 identifiers (RFC 9562), matching the database default {@code uuidv7()}. */
public final class Ids {
    private Ids() {}

    /** Fixed namespace for Meridian deterministic (name-based, v5) ids. */
    private static final UUID NAMESPACE = UUID.fromString("6d657269-6469-5061-6e00-6d6572696469");

    public static UUID newId() {
        return UuidCreator.getTimeOrderedEpoch();
    }

    /**
     * A deterministic (name-based, RFC 9562 v5/SHA-1) UUID of {@code key}. The same key always yields the same id, so
     * synthetic rows compiled from a stable key keep their id across refreshes (and their usage counters with it).
     */
    public static UUID deterministic(String key) {
        return UuidCreator.getNameBasedSha1(NAMESPACE, key);
    }
}
