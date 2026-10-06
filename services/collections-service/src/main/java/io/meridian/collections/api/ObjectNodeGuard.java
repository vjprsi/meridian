package io.meridian.collections.api;

import io.meridian.collections.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Guards for generic PATCH on rows whose lifecycle has ended. */
final class ObjectNodeGuard {
    private ObjectNodeGuard() {}

    /** 404 for unknown rows; 409 {@code code} when the row's status is one of {@code terminal}. */
    static JsonNode notTerminal(Rows rows, String table, UUID id, Set<String> terminal, String code) {
        JsonNode row = rows.get(table, id);
        String status = Rows.str(row, "status");
        if (terminal.contains(status)) {
            throw ApiException.conflict(code, table.replace('_', ' ') + " " + id + " is " + status + " and can no longer be changed");
        }
        return row;
    }
}
