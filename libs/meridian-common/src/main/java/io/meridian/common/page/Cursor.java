package io.meridian.common.page;

import io.meridian.common.error.ApiException;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.UUID;

/** Opaque keyset cursor over time-ordered UUIDv7 ids. Lists are ordered by id descending (newest first). */
public final class Cursor {
    private Cursor() {}

    public static String encode(UUID lastId) {
        ByteBuffer b = ByteBuffer.allocate(16);
        b.putLong(lastId.getMostSignificantBits()).putLong(lastId.getLeastSignificantBits());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b.array());
    }

    public static UUID decode(String cursor) {
        try {
            ByteBuffer b = ByteBuffer.wrap(Base64.getUrlDecoder().decode(cursor));
            return new UUID(b.getLong(), b.getLong());
        } catch (RuntimeException e) {
            throw ApiException.badRequest("invalid_cursor", "cursor is not valid");
        }
    }

    /** Clamp a requested page size to 1..200, default 50. */
    public static int limit(Integer requested) {
        if (requested == null) {
            return 50;
        }
        return Math.max(1, Math.min(200, requested));
    }
}
