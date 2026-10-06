package io.meridian.document.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Logic-less {@code {{variable}}} renderer. Only dotted variable paths are supported: there are no helpers,
 * partials, raw blocks or expressions, so a template can never execute code. Values are HTML-escaped when the
 * output is markup.
 */
public final class TemplateRenderer {
    private static final Pattern TAG = Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*)\\s*}}");

    private TemplateRenderer() {}

    public record Result(String output, List<String> missing) {}

    /** Thrown for malformed templates (unbalanced or unsupported tags). */
    public static final class SyntaxError extends RuntimeException {
        public SyntaxError(String message) {
            super(message);
        }
    }

    /** Checks the template only uses supported {@code {{name}}} tags. */
    public static void validate(String template) {
        if (template == null) {
            return;
        }
        String stripped = TAG.matcher(template).replaceAll("");
        int open = stripped.indexOf("{{");
        int close = stripped.indexOf("}}");
        if (open >= 0 || close >= 0) {
            int at = open >= 0 ? open : close;
            throw new SyntaxError("Unsupported or unbalanced tag near position " + at
                    + "; only {{variable}} or {{object.field}} tags are allowed");
        }
    }

    /** Variables referenced by the template, in order of first appearance. */
    public static Set<String> variables(String template) {
        Set<String> names = new LinkedHashSet<>();
        if (template != null) {
            Matcher m = TAG.matcher(template);
            while (m.find()) {
                names.add(m.group(1));
            }
        }
        return names;
    }

    /**
     * Renders the template. Missing (null) variables render as an empty string and are reported in
     * {@link Result#missing()}; callers decide whether that is an error.
     */
    public static Result render(String template, Map<String, ?> data, boolean htmlEscape) {
        validate(template);
        if (template == null) {
            return new Result("", List.of());
        }
        List<String> missing = new ArrayList<>();
        Matcher m = TAG.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            Object value = resolve(data, m.group(1));
            String text;
            if (value == null) {
                if (!missing.contains(m.group(1))) {
                    missing.add(m.group(1));
                }
                text = "";
            } else {
                text = String.valueOf(value);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(htmlEscape ? escapeHtml(text) : text));
        }
        m.appendTail(out);
        return new Result(out.toString(), List.copyOf(missing));
    }

    static Object resolve(Map<String, ?> data, String path) {
        Object current = data;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(part);
        }
        return current instanceof Map<?, ?> || current instanceof List<?> ? null : current;
    }

    public static String escapeHtml(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }
}
