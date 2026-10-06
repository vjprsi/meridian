package io.meridian.crm.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** {@code {{placeholder}}} templates of canned responses: validation and rendering. */
public final class Templates {
    private Templates() {}

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z][a-zA-Z0-9_.]*)\\s*}}");

    public record Rendered(String text, List<String> used, List<String> unresolved) {}

    /** Problems with the template syntax (unbalanced or malformed placeholders); empty when valid. */
    public static List<String> validate(String body) {
        List<String> problems = new ArrayList<>();
        if (body == null || body.isBlank()) {
            problems.add("body is empty");
            return problems;
        }
        String stripped = PLACEHOLDER.matcher(body).replaceAll("");
        if (stripped.contains("{{") || stripped.contains("}}")) {
            problems.add("malformed placeholder: use {{name}} with letters, digits, '_' and '.'");
        }
        return problems;
    }

    public static Set<String> placeholders(String body) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = PLACEHOLDER.matcher(body == null ? "" : body);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** Replaces each placeholder with its value; unknown placeholders are left in place and reported. */
    public static Rendered render(String body, Map<String, String> values) {
        Set<String> used = new LinkedHashSet<>();
        Set<String> unresolved = new LinkedHashSet<>();
        Matcher m = PLACEHOLDER.matcher(body);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String v = values.get(name);
            if (v == null) {
                unresolved.add(name);
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
            } else {
                used.add(name);
                m.appendReplacement(sb, Matcher.quoteReplacement(v));
            }
        }
        m.appendTail(sb);
        return new Rendered(sb.toString(), List.copyOf(used), List.copyOf(unresolved));
    }
}
