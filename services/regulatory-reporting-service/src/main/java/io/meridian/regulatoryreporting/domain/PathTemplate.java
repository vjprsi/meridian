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
    private static final Set<String> BUILT_INS = Set.of("as_of_date", "period_start", "period_end", "legal_entity_id",
            "reporting_year");

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
        check(path);
        Matcher m = PLACEHOLDER.matcher(path);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            Object v = name.startsWith("param.") ? params.get(name.substring(6)) : builtIns.get(name);
            if (v == null) {
                throw new IllegalArgumentException("no value for placeholder {" + name + "}");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(URLEncoder.encode(String.valueOf(v), StandardCharsets.UTF_8)));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
