package io.meridian.search.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Text analysis for the PostgreSQL full-text engine: searchable fields with boosts, weighted document text
 * (A/B/C weights by boost), PostgreSQL tsquery construction with synonym expansion and prefix matching, and
 * field masking for callers without PII permission.
 */
public final class SearchText {
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+");

    private SearchText() {}

    /** A searchable field: dotted JSON path and boost (1 = normal). */
    public record Field(String path, double boost) {}

    /**
     * Parses {@code searchable_fields}: an array (or object holding one) of {@code "name^3"} / {@code "name"}
     * strings, or an object mapping field -> boost ({@code {"full_name": 3, "email": 1}}).
     */
    public static List<Field> fields(JsonNode node) {
        List<Field> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        List<String> specs = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(n -> specs.add(n.asString()));
        } else if (node.isObject()) {
            boolean arrays = false;
            for (String k : node.propertyNames()) {
                if (node.get(k).isArray()) {
                    arrays = true;
                    node.get(k).forEach(n -> specs.add(n.asString()));
                }
            }
            if (!arrays) {
                for (String k : node.propertyNames()) {
                    JsonNode v = node.get(k);
                    out.add(new Field(k, v.isNumber() ? v.asDouble() : 1.0));
                }
            }
        }
        for (String s : specs) {
            int caret = s.indexOf('^');
            if (caret > 0) {
                out.add(new Field(s.substring(0, caret).trim(), Double.parseDouble(s.substring(caret + 1).trim())));
            } else if (!s.isBlank()) {
                out.add(new Field(s.trim(), 1.0));
            }
        }
        return out;
    }

    /** Field values grouped by weight: index 0 = A (boost >= 3), 1 = B (boost >= 2), 2 = C. */
    public static String[] weightedText(JsonNode doc, List<Field> fields) {
        StringBuilder[] w = {new StringBuilder(), new StringBuilder(), new StringBuilder()};
        for (Field f : fields) {
            String v = text(value(doc, f.path()));
            if (v.isEmpty()) {
                continue;
            }
            int slot = f.boost() >= 3 ? 0 : f.boost() >= 2 ? 1 : 2;
            w[slot].append(v).append(' ');
            // digits-only variant so "+254 700 123 456" matches "254700123456"
            String digits = v.replaceAll("\\D", "");
            if (digits.length() >= 6 && !digits.equals(v)) {
                w[slot].append(digits).append(' ');
            }
        }
        return new String[]{w[0].toString().trim(), w[1].toString().trim(), w[2].toString().trim()};
    }

    /** The display title: the value of the most boosted searchable field. */
    public static String title(JsonNode doc, List<Field> fields) {
        return fields.stream().sorted((a, b) -> Double.compare(b.boost(), a.boost()))
                .map(f -> text(value(doc, f.path()))).filter(s -> !s.isEmpty()).findFirst().orElse(null);
    }

    public static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        Matcher m = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    /**
     * Builds a {@code to_tsquery('simple', ...)} expression: every query token must match (AND), each token
     * OR-ed with its synonyms, the last token also as a prefix ({@code :*}) for search-as-you-type.
     * Returns null when the text has no searchable tokens.
     */
    public static String tsquery(String queryText, List<Set<String>> synonymSets) {
        List<String> tokens = tokens(queryText);
        if (tokens.isEmpty()) {
            return null;
        }
        Map<String, Set<String>> syn = new LinkedHashMap<>();
        for (Set<String> set : synonymSets) {
            for (String t : set) {
                syn.computeIfAbsent(t, k -> new LinkedHashSet<>()).addAll(set);
            }
        }
        List<String> groups = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            Set<String> alts = new LinkedHashSet<>();
            alts.add(t);
            alts.addAll(syn.getOrDefault(t, Set.of()));
            List<String> terms = new ArrayList<>();
            for (String a : alts) {
                for (String part : tokens(a)) {
                    terms.add(part);
                }
            }
            if (i == tokens.size() - 1) {
                terms.add(t + ":*");
            }
            groups.add(terms.size() == 1 ? terms.getFirst() : "(" + String.join(" | ", new LinkedHashSet<>(terms)) + ")");
        }
        return String.join(" & ", groups);
    }

    /** Masks a value, keeping a hint: e-mail domain, or the last four characters. */
    public static String mask(String v) {
        if (v == null || v.isEmpty()) {
            return v;
        }
        int at = v.indexOf('@');
        if (at > 0) {
            return v.charAt(0) + "***" + v.substring(at);
        }
        if (v.length() <= 4) {
            return "****";
        }
        return "*".repeat(Math.min(8, v.length() - 4)) + v.substring(v.length() - 4);
    }

    public static JsonNode value(JsonNode doc, String path) {
        JsonNode n = doc;
        for (String p : path.split("\\.")) {
            if (n == null) {
                return null;
            }
            n = n.get(p);
        }
        return n;
    }

    private static String text(JsonNode n) {
        if (n == null || n.isNull()) {
            return "";
        }
        if (n.isValueNode()) {
            return n.asString();
        }
        StringBuilder sb = new StringBuilder();
        n.forEach(c -> sb.append(text(c)).append(' '));
        return sb.toString().trim();
    }
}
