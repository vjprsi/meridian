package io.meridian.kyc.application;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.web.crud.CrudEngine;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Shared helpers of the application layer: events with API payloads, list-valued json columns, field access. */
@Component
public class KycSupport {
    private final OutboxWriter outbox;
    private final CrudEngine crud;
    private final JsonMapper json;

    public KycSupport(OutboxWriter outbox, CrudEngine crud, JsonMapper json) {
        this.outbox = outbox;
        this.crud = crud;
        this.json = json;
    }

    /** Publishes an event whose payload is the entity's API representation (hidden/secret columns never leak). */
    public void publish(String eventType, String aggregateType, JsonNode row, Class<?> model) {
        Object api = crud.read(json.writeValueAsString(row), model);
        outbox.publish(eventType, aggregateType, uuid(row, "id"), json.valueToTree(api));
    }

    public <T> T read(JsonNode row, Class<T> model) {
        return crud.read(json.writeValueAsString(row), model);
    }

    public ObjectNode object() {
        return json.createObjectNode();
    }

    public JsonNode tree(Object o) {
        return o == null ? json.createObjectNode() : json.valueToTree(o);
    }

    /** Writable column values of an API model (snake_case, nulls dropped). */
    public ObjectNode columns(String table, Object body) {
        return crud.toColumns(table, body);
    }

    /** Row JSON text (row_to_json) as a tree. */
    public ObjectNode parse(String rowJson) {
        return (ObjectNode) json.readTree(rowJson);
    }

    /** row_version from an If-Match header. */
    public static int version(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw io.meridian.common.error.ApiException.badRequest("if_match_required",
                    "If-Match header with the current row_version is required");
        }
        try {
            return Integer.parseInt(ifMatch.replace("W/", "").replace("\"", "").trim());
        } catch (NumberFormatException e) {
            throw io.meridian.common.error.ApiException.badRequest("invalid_if_match", "If-Match must be the resource's row_version");
        }
    }

    /** The string values of a list-valued (json[]) column; empty for null / missing. */
    public static List<String> values(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n != null && n.isArray()) {
            n.forEach(v -> out.add(v.asString()));
        }
        return out;
    }

    public ArrayNode array(Collection<String> values) {
        ArrayNode a = json.createArrayNode();
        values.forEach(a::add);
        return a;
    }

    public static String text(JsonNode n, String field) {
        return n != null && n.hasNonNull(field) ? n.get(field).asString() : null;
    }

    public static UUID uuid(JsonNode n, String field) {
        String s = text(n, field);
        return s == null ? null : UUID.fromString(s);
    }

    public static LocalDate date(JsonNode n, String field) {
        String s = text(n, field);
        return s == null ? null : LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
    }

    public static boolean bool(JsonNode n, String field) {
        return n != null && n.hasNonNull(field) && n.get(field).asBoolean();
    }

    public static String principal() {
        return TenantContext.principal();
    }

    /** The principal as an app_user id when it is one (JWT subject), else null. */
    public static UUID principalId() {
        try {
            return UUID.fromString(TenantContext.principal());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    public static String now() {
        return io.meridian.common.time.Clocks.now().toString();
    }
}
