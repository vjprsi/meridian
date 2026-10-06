package io.meridian.common.id;

import com.github.f4b6a3.uuid.UuidCreator;
import java.util.UUID;

/** Time-ordered UUIDv7 identifiers (RFC 9562), matching the database default {@code uuidv7()}. */
public final class Ids {
    private Ids() {}

    public static UUID newId() {
        return UuidCreator.getTimeOrderedEpoch();
    }
}
