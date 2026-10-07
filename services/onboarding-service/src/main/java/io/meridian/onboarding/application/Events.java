package io.meridian.onboarding.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.web.crud.CrudEngine;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Outbox publishing with the API representation as payload, plus small shared helpers for use cases. */
@Component
public class Events {
    private final OutboxWriter outbox;
    private final CrudEngine crud;
    private final JsonMapper json;

    public Events(OutboxWriter outbox, CrudEngine crud, JsonMapper json) {
        this.outbox = outbox;
        this.crud = crud;
        this.json = json;
    }

    public void publish(String eventType, String aggregate, ObjectNode row, Class<?> model) {
        Object representation = crud.read(json.writeValueAsString(row), model);
        outbox.publish(eventType, aggregate, UUID.fromString(row.get("id").asString()), json.valueToTree(representation));
    }

    public static String now() {
        return io.meridian.common.time.Clocks.now().toString();
    }

    public static OffsetDateTime ts(ObjectNode row, String field) {
        return row.hasNonNull(field) ? OffsetDateTime.parse(row.get(field).asString()) : null;
    }

    public static UUID uuid(ObjectNode row, String field) {
        return row.hasNonNull(field) ? UUID.fromString(row.get(field).asString()) : null;
    }

    public static int parseIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw ApiException.badRequest("if_match_required", "If-Match header with the current row_version is required");
        }
        try {
            return Integer.parseInt(ifMatch.replace("W/", "").replace("\"", "").trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("invalid_if_match", "If-Match must be the resource's row_version");
        }
    }

    /** Maker-checker: the acting principal must differ from each of the given makers. */
    public static void fourEyes(String... makers) {
        String p = TenantContext.principal();
        boolean anyMaker = false;
        for (String m : makers) {
            if (m != null && !m.isBlank()) {
                anyMaker = true;
                io.meridian.common.tenancy.FourEyes.require(m, p, "The same user cannot both prepare and approve this change");
            }
        }
        if (!anyMaker) {
            io.meridian.common.tenancy.FourEyes.require(null, p, "The same user cannot both prepare and approve this change");
        }
    }

    public static UUID principalUuid() {
        try {
            return UUID.fromString(TenantContext.principal());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
