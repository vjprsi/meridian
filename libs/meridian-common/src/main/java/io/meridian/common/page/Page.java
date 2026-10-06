package io.meridian.common.page;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** One page of a keyset-paginated list. Fetch {@code limit + 1} rows and call {@link #of}. */
public record Page<T>(List<T> data, String nextCursor, boolean hasMore) {

    public static <T> Page<T> of(List<T> rowsPlusOne, int limit, Function<T, UUID> id) {
        boolean more = rowsPlusOne.size() > limit;
        List<T> data = more ? rowsPlusOne.subList(0, limit) : rowsPlusOne;
        String next = more ? Cursor.encode(id.apply(data.get(data.size() - 1))) : null;
        return new Page<>(List.copyOf(data), next, more);
    }
}
