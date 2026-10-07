package io.meridian.regulatoryreporting.domain;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Data source paths with placeholders, e.g. {@code /v1/gl/periods/{param.period_id}/trial-balance} or
 * {@code /v1/loans?legal_entity_id={legal_entity_id}&status=ACTIVE}. Only relative {@code /v1/} API paths are allowed
 * and every substituted value is URL-encoded, so a parameter can never change the target service or path.
 */
public final class PathTemplate {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_][a-z0-9_.]*)}");
    private static final Pattern SAFE = Pattern.compile("/v1/[A-Za-z0-9_\\-./{}?&=,:%]*");
    /**
     * Built-in placeholders; {@code period_start_ts} is the period start at 00:00 UTC and {@code period_end_ts} the day
     * after the period end at 00:00 UTC (exclusive bound for {@code created_before}-style filters).
     */
    public static final Set<String> BUILT_INS = Set.of("as_of_date", "period_start", "period_end", "legal_entity_id",
            "reporting_year", "period_start_ts", "period_end_ts");

    private PathTemplate() {}

    public static boolean isSafe(String path) {
        return path != null && path.length() <= 500 && SAFE.matcher(path).matches() && !path.contains("..")
                && !path.contains("//");
    }

    /** Validates placeholders (built-ins or {@code param.<name>}). */
    public static void check(String path) {
        Matcher m = PLACEHOLDER.matcher(path);
        while (m.find()) {
            String name = m.group(1);
            if (!BUILT_INS.contains(name) && !name.startsWith("param.")) {
                throw new IllegalArgumentException("unknown placeholder {" + name + "}; use " + BUILT_INS + " or {param.<name>}");
            }
        }
        String stripped = PLACEHOLDER.matcher(path).replaceAll("");
        if (stripped.contains("{") || stripped.contains("}")) {
            throw new IllegalArgumentException("malformed placeholder in path");
        }
    }

    /** Substitutes placeholders with URL-encoded values; a missing value is an error. */
    public static String expand(String path, Map<String, Object> builtIns, Map<String, Object> params) {
        return substitute(path, builtIns, params, true);
    }

    /** Substitutes placeholders in a plain value template (no URL encoding); a missing value is an error. */
    public static String text(String template, Map<String, Object> builtIns, Map<String, Object> params) {
        return substitute(template, builtIns, params, false);
    }

    private static String substitute(String path, Map<String, Object> builtIns, Map<String, Object> params, boolean encode) {
        check(path);
        Matcher m = PLACEHOLDER.matcher(path);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            Object v = name.startsWith("param.") ? params.get(name.substring(6)) : builtIns.get(name);
            if (v == null) {
                throw new IllegalArgumentException("no value for placeholder {" + name + "}");
            }
            String s = encode ? URLEncoder.encode(String.valueOf(v), StandardCharsets.UTF_8) : String.valueOf(v);
            m.appendReplacement(sb, Matcher.quoteReplacement(s));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
