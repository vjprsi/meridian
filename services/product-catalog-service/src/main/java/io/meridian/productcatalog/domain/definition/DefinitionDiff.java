package io.meridian.productcatalog.domain.definition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Structured differences between two product-version definitions (resolved snapshots). Entries of each section
 * are matched by a business key (parameter key, rate kind, fee code, limit code + KYC tier, rule type + operator,
 * script hook + name); for matched entries every changed field is reported with its old and new value.
 * Numbers compare by value (scale-insensitive).
 */
public final class DefinitionDiff {
    private DefinitionDiff() {}

    /** Sections of a resolved definition and how their entries are keyed. */
    public static final Map<String, Function<Map<String, Object>, String>> SECTIONS = sections();

    private static Map<String, Function<Map<String, Object>, String>> sections() {
        Map<String, Function<Map<String, Object>, String>> m = new LinkedHashMap<>();
        m.put("parameters", e -> str(e.get("param_key")));
        m.put("interest_rates", e -> str(e.get("rate_kind")) + "@" + str(e.get("effective_from")));
        m.put("fees", e -> str(e.get("code")));
        m.put("limits", e -> str(e.get("limit_code")) + "|tier=" + str(e.get("kyc_tier")) + "|" + str(e.get("channel")));
        m.put("eligibility_rules", e -> str(e.get("rule_type")) + "|" + str(e.get("operator")));
        m.put("scripts", e -> str(e.get("hook")) + "|" + str(e.get("name")));
        return m;
    }

    /**
     * Differences going from {@code from} to {@code to}.
     *
     * @return {"identical", "change_count", "sections": {section: {"added", "removed", "changed"}}, "other": [...]}
     */
    public static Map<String, Object> diff(Map<String, Object> from, Map<String, Object> to) {
        Map<String, Object> sections = new LinkedHashMap<>();
        int count = 0;
        for (Map.Entry<String, Function<Map<String, Object>, String>> s : SECTIONS.entrySet()) {
            Map<String, Map<String, Object>> a = index(from.get(s.getKey()), s.getValue());
            Map<String, Map<String, Object>> b = index(to.get(s.getKey()), s.getValue());
            List<Object> added = new ArrayList<>();
            List<Object> removed = new ArrayList<>();
            List<Object> changed = new ArrayList<>();
            Set<String> keys = new LinkedHashSet<>(a.keySet());
            keys.addAll(b.keySet());
            for (String k : keys) {
                Map<String, Object> x = a.get(k);
                Map<String, Object> y = b.get(k);
                if (x == null) {
                    added.add(entry(k, y));
                } else if (y == null) {
                    removed.add(entry(k, x));
                } else {
                    List<Object> fields = fieldChanges(x, y);
                    if (!fields.isEmpty()) {
                        Map<String, Object> c = new LinkedHashMap<>();
                        c.put("key", k);
                        c.put("changes", fields);
                        changed.add(c);
                    }
                }
            }
            count += added.size() + removed.size() + changed.size();
            Map<String, Object> sec = new LinkedHashMap<>();
            sec.put("added", added);
            sec.put("removed", removed);
            sec.put("changed", changed);
            sections.put(s.getKey(), sec);
        }
        Set<String> other = new TreeSet<>();
        other.addAll(from.keySet());
        other.addAll(to.keySet());
        other.removeAll(SECTIONS.keySet());
        List<Object> otherChanges = new ArrayList<>();
        for (String k : other) {
            if (!Canonical.of(from.get(k)).equals(Canonical.of(to.get(k)))) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("field", k);
                c.put("from", from.get(k));
                c.put("to", to.get(k));
                otherChanges.add(c);
            }
        }
        count += otherChanges.size();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("identical", count == 0);
        out.put("change_count", count);
        out.put("sections", sections);
        out.put("other", otherChanges);
        return out;
    }

    private static List<Object> fieldChanges(Map<String, Object> x, Map<String, Object> y) {
        Set<String> fields = new TreeSet<>(x.keySet());
        fields.addAll(y.keySet());
        List<Object> out = new ArrayList<>();
        for (String f : fields) {
            Object a = x.get(f);
            Object b = y.get(f);
            if (!Canonical.of(a).equals(Canonical.of(b))) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("field", f);
                c.put("from", a);
                c.put("to", b);
                out.add(c);
            }
        }
        return out;
    }

    private static Map<String, Object> entry(String key, Map<String, Object> value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("value", value);
        return m;
    }

    /** Indexes a section's entries by key; duplicate keys get #2, #3 ... suffixes in order. */
    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> index(Object section, Function<Map<String, Object>, String> keyFn) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (!(section instanceof List<?> list)) {
            return out;
        }
        Map<String, Integer> seen = new HashMap<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                String base = keyFn.apply((Map<String, Object>) m);
                int n = seen.merge(base, 1, Integer::sum);
                out.put(n == 1 ? base : base + "#" + n, (Map<String, Object>) m);
            }
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? "*" : o.toString();
    }
}
