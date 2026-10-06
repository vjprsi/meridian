package io.meridian.businessday.application;

import io.meridian.common.error.ApiException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The cross-service job contract: the target of an EOD job is {@code <http_method> <owning_service><http_path>}
 * with an optional JSON {@code body_template}. Placeholders ({@code {business_date}}, {@code {partition_no}}, ...)
 * are filled from the job context: URL-encoded in the path, and in the body a string that is exactly one
 * placeholder takes the typed context value while other strings get textual substitution.
 */
public final class JobTemplates {
    /** Placeholders a job target may use (all are fields of the job context). */
    public static final Set<String> PLACEHOLDERS = Set.of("business_date", "legal_entity_id", "cycle_type", "eod_run_id",
            "eod_job_run_id", "job_code", "job_type", "partition_no", "partition_count", "partition_key", "attempt");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)}");
    private static final int MAX_KEY = 128;

    private JobTemplates() {}

    /** Idempotency-Key of one EOD job partition: identical for every attempt, unique per run, job and partition. */
    public static String eodKey(UUID runId, String jobCode, int partitionNo) {
        return "eod:" + runId + ":" + jobCode + ":" + partitionNo;
    }

    /** Idempotency-Key of a manual schedule run, derived from the caller's own key (hashed when too long). */
    public static String scheduleKey(UUID scheduleId, String callerKey) {
        String key = "jsr:" + scheduleId + ":" + callerKey;
        return key.length() <= MAX_KEY ? key : "jsr:" + scheduleId + ":" + sha256(callerKey);
    }

    /** A service-relative path: starts with '/', no scheme, no parent segments. */
    public static void requireServicePath(String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("//") || path.contains("..") || path.contains("://")) {
            throw ApiException.unprocessable("invalid_http_path", "http_path must be a service-relative path starting with /");
        }
    }

    /** Validates the path and body template placeholders against {@link #PLACEHOLDERS}. */
    public static void validate(String path, JsonNode bodyTemplate) {
        requireServicePath(path);
        checkPlaceholders(path);
        if (bodyTemplate != null && !bodyTemplate.isNull()) {
            if (!bodyTemplate.isObject()) {
                throw ApiException.unprocessable("invalid_body_template", "body_template must be a JSON object");
            }
            walk(bodyTemplate);
        }
    }

    /** The path with placeholders replaced by URL-encoded context values. */
    public static String renderPath(String path, JsonNode context) {
        Matcher m = PLACEHOLDER.matcher(path);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String v = context.path(m.group(1)).asString("");
            m.appendReplacement(out, Matcher.quoteReplacement(URLEncoder.encode(v, StandardCharsets.UTF_8)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** The body template with placeholders replaced (a deep copy; the template is not modified). */
    public static JsonNode renderBody(JsonNode template, JsonNode context) {
        if (template.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            for (Map.Entry<String, JsonNode> e : template.properties()) {
                out.set(e.getKey(), renderBody(e.getValue(), context));
            }
            return out;
        }
        if (template.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            template.forEach(v -> out.add(renderBody(v, context)));
            return out;
        }
        if (template.isString()) {
            String s = template.asString();
            Matcher whole = PLACEHOLDER.matcher(s);
            if (whole.matches()) {
                JsonNode v = context.get(whole.group(1));
                return v == null ? JsonNodeFactory.instance.nullNode() : v.deepCopy();
            }
            Matcher m = PLACEHOLDER.matcher(s);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(out, Matcher.quoteReplacement(context.path(m.group(1)).asString("")));
            }
            m.appendTail(out);
            return JsonNodeFactory.instance.stringNode(out.toString());
        }
        return template.deepCopy();
    }

    private static void walk(JsonNode n) {
        if (n.isString()) {
            checkPlaceholders(n.asString());
        } else if (n.isContainer()) {
            for (JsonNode child : n) {
                walk(child);
            }
        }
    }

    private static void checkPlaceholders(String s) {
        Matcher m = PLACEHOLDER.matcher(s);
        while (m.find()) {
            if (!PLACEHOLDERS.contains(m.group(1))) {
                throw ApiException.unprocessable("unknown_placeholder", "Unknown placeholder {" + m.group(1)
                        + "}; allowed: " + PLACEHOLDERS.stream().sorted().toList());
            }
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
