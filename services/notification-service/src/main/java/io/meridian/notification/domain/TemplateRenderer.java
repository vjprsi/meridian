package io.meridian.notification.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Logic-less, Mustache-style placeholder rendering. Supported tags: {@code {{name}}} (escaped when the output is
 * HTML) and {@code {{{name}}}} (raw). Names may use dotted paths into nested maps ({@code {{account.last4}}}).
 * Nothing is ever evaluated: no sections, partials, lambdas or expressions, so templates cannot execute code.
 */
public final class TemplateRenderer {
    private static final Pattern TAG = Pattern.compile("\\{\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}}|\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}");
    private static final Pattern ANY_OPEN = Pattern.compile("\\{\\{");

    private TemplateRenderer() {}

    /** Thrown when variables referenced by the template are absent. */
    public static final class MissingVariablesException extends RuntimeException {
        private final List<String> names;

        public MissingVariablesException(List<String> names) {
            super("Missing template variables: " + String.join(", ", names));
            this.names = List.copyOf(names);
        }

        public List<String> names() {
            return names;
        }
    }

    /** Thrown when the template contains malformed tags (e.g. an unterminated or unsupported {{...}}). */
    public static final class InvalidTemplateException extends RuntimeException {
        public InvalidTemplateException(String message) {
            super(message);
        }
    }

    /** Validates syntax and returns the variable names referenced, in order of first use. */
    public static Set<String> variables(String template) {
        Set<String> names = new LinkedHashSet<>();
        if (template == null) {
            return names;
        }
        Matcher m = TAG.matcher(template);
        int validTags = 0;
        while (m.find()) {
            names.add(m.group(1) != null ? m.group(1) : m.group(2));
            validTags++;
        }
        Matcher open = ANY_OPEN.matcher(template.replace("{{{", "{{"));
        int opens = 0;
        while (open.find()) {
            opens++;
        }
        if (opens != validTags) {
            throw new InvalidTemplateException(
                    "Template contains malformed or unsupported tags; only {{name}} and {{{name}}} are allowed");
        }
        return names;
    }

    /**
     * Renders the template.
     *
     * @param html   escape values for HTML output ({{{raw}}} tags are never escaped)
     * @param strict fail with {@link MissingVariablesException} when a variable is missing; otherwise the tag is
     *               replaced with {@code [name]} (used for test sends with sample data)
     */
    public static String render(String template, Map<String, ?> vars, boolean html, boolean strict) {
        if (template == null) {
            return null;
        }
        variables(template);
        Matcher m = TAG.matcher(template);
        StringBuilder out = new StringBuilder();
        List<String> missing = new ArrayList<>();
        while (m.find()) {
            boolean raw = m.group(1) != null;
            String name = raw ? m.group(1) : m.group(2);
            Object value = lookup(vars, name);
            String text;
            if (value == null) {
                if (!missing.contains(name)) {
                    missing.add(name);
                }
                text = "[" + name + "]";
            } else {
                text = String.valueOf(value);
                if (html && !raw) {
                    text = escapeHtml(text);
                }
            }
            m.appendReplacement(out, Matcher.quoteReplacement(text));
        }
        m.appendTail(out);
        if (strict && !missing.isEmpty()) {
            throw new MissingVariablesException(missing);
        }
        return out.toString();
    }

    static Object lookup(Map<String, ?> vars, String path) {
        if (vars == null) {
            return null;
        }
        Object current = vars;
        for (String part : path.split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(part);
            } else {
                return null;
            }
        }
        if (current instanceof Map<?, ?> || current instanceof List<?>) {
            return null; // only scalars are substituted
        }
        return current;
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
                case '/' -> b.append("&#x2F;");
                case '`' -> b.append("&#x60;");
                case '=' -> b.append("&#x3D;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }
}
