package io.meridian.payment.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Idempotent execution of POST actions/creates, row-to-model mapping and raw responses for controllers. */
@Component
class Actions {
    private final Idempotency idempotency;
    private final JsonMapper json;
    private final CrudEngine crud;

    Actions(Idempotency idempotency, JsonMapper json, CrudEngine crud) {
        this.idempotency = idempotency;
        this.json = json;
        this.crud = crud;
    }

    /** An action on {@code id}: exactly-once per Idempotency-Key, 200 with the resulting row. */
    <T> ResponseEntity<T> action(String key, UUID id, String action, Object body, Class<T> type, Supplier<JsonNode> work) {
        TenantContext.require();
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("action", action);
        if (id != null) {
            fingerprint.put("id", id.toString());
        }
        if (body != null) {
            fingerprint.put("body", tree(body));
        }
        return idempotency.execute(key, fingerprint, type, () -> ResponseEntity.ok(model(work.get(), type)));
    }

    /** A create with business rules: exactly-once per Idempotency-Key, 201 with the new row. */
    <T> ResponseEntity<T> create(String key, Object body, Class<T> type, Supplier<JsonNode> work) {
        TenantContext.require();
        return idempotency.execute(key, body, type,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(model(work.get(), type)));
    }

    /** Idempotent action whose response is a free-form JSON object. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    ResponseEntity<Map<String, Object>> object(String key, UUID id, String action, Object body, Supplier<JsonNode> work) {
        TenantContext.require();
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("action", action);
        if (id != null) {
            fingerprint.put("id", id.toString());
        }
        if (body != null) {
            fingerprint.put("body", tree(body));
        }
        ResponseEntity r = idempotency.execute(key, fingerprint, Map.class,
                () -> ResponseEntity.ok(json.treeToValue(work.get(), Map.class)));
        return (ResponseEntity<Map<String, Object>>) r;
    }

    /** Read-only computed JSON object. */
    @SuppressWarnings("unchecked")
    ResponseEntity<Map<String, Object>> read(JsonNode result) {
        TenantContext.require();
        return ResponseEntity.ok(json.treeToValue(result, Map.class));
    }

    /** A sub-resource page (cursor paging) of {@code table} filtered by {@code column = id}. */
    <T> ResponseEntity<T> page(String table, String column, UUID id, Integer limit, String cursor, Class<T> pageType) {
        return crud.list(table, pageType, Crud.params().with(column, id).with("limit", limit).with("cursor", cursor));
    }

    /** Writes bytes directly (binary or text documents) and completes the response. */
    <T> ResponseEntity<T> raw(byte[] content, String contentType, String filename) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) || a.getResponse() == null) {
            throw new IllegalStateException("no current response");
        }
        HttpServletResponse res = a.getResponse();
        res.setStatus(200);
        res.setContentType(contentType);
        if (filename != null) {
            res.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        }
        res.setContentLength(content.length);
        try {
            res.getOutputStream().write(content);
            res.flushBuffer();
        } catch (IOException e) {
            throw new IllegalStateException("could not write response", e);
        }
        return null;
    }

    <T> T model(JsonNode row, Class<T> type) {
        return json.treeToValue(row, type);
    }

    JsonNode tree(Object body) {
        return body == null ? json.createObjectNode() : json.valueToTree(body);
    }

    static int version(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw ApiException.badRequest("if_match_required", "If-Match header with the current row_version is required");
        }
        try {
            return Integer.parseInt(ifMatch.replace("W/", "").replace("\"", "").trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("invalid_if_match", "If-Match must be the resource's row_version");
        }
    }

    static String text(JsonNode n, String f) {
        JsonNode v = n == null ? null : n.get(f);
        return v == null || v.isNull() ? null : v.asString();
    }

    static UUID uuid(JsonNode n, String f) {
        String s = text(n, f);
        return s == null ? null : UUID.fromString(s);
    }

    static java.math.BigDecimal decimal(JsonNode n, String f) {
        String s = text(n, f);
        return s == null ? null : new java.math.BigDecimal(s);
    }
}
