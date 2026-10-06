package io.meridian.routing.api;

import io.meridian.common.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Request/response helpers for operations whose generated signature cannot carry what the contract describes:
 * query parameters the contract mentions only in prose (e.g. {@code ?from=&to=}) and {@code Void} operations
 * that return a JSON document or a file. Those responses are written directly to the servlet response.
 */
final class Http {
    private Http() {}

    static HttpServletRequest request() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) {
            return a.getRequest();
        }
        throw new IllegalStateException("no current request");
    }

    private static HttpServletResponse response() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a && a.getResponse() != null) {
            return a.getResponse();
        }
        throw new IllegalStateException("no current response");
    }

    static String query(String name) {
        String v = request().getParameter(name);
        return v == null || v.isBlank() ? null : v.trim();
    }

    static OffsetDateTime queryTime(String name) {
        String v = query(name);
        if (v == null) {
            return null;
        }
        try {
            return v.length() == 10 ? java.time.LocalDate.parse(v).atStartOfDay().atOffset(java.time.ZoneOffset.UTC)
                    : OffsetDateTime.parse(v);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("invalid_" + name, name + " must be an ISO date or date-time");
        }
    }

    static Integer queryInt(String name, int dflt) {
        String v = query(name);
        if (v == null) {
            return dflt;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("invalid_" + name, name + " must be an integer");
        }
    }

    /** Writes a JSON body with status 200 and returns null (the response is complete). */
    static ResponseEntity<Void> json(JsonMapper mapper, Object body) {
        return write(200, "application/json", mapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8), null, null);
    }

    static ResponseEntity<Void> text(String content, String filename) {
        return write(200, "text/plain;charset=UTF-8", content.getBytes(StandardCharsets.UTF_8), filename, null);
    }

    static ResponseEntity<Void> write(int status, String contentType, byte[] content, String filename, String replayed) {
        HttpServletResponse res = response();
        res.setStatus(status);
        res.setContentType(contentType);
        if (filename != null) {
            res.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        }
        if (replayed != null) {
            res.setHeader("Idempotent-Replayed", replayed);
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

    static String text(JsonNode n, String f) {
        JsonNode v = n == null ? null : n.get(f);
        return v == null || v.isNull() ? null : v.asString();
    }

    static BigDecimal decimal(JsonNode n, String f) {
        String s = text(n, f);
        return s == null ? null : new BigDecimal(s);
    }
}
