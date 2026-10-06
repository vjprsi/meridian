package io.meridian.sanctions.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory blocking index over list entries: each name token contributes a few cheap keys (2-letter prefix, prefix
 * with a neutralised leading vowel, 3-letter suffix) so that misspellings and transliteration variants still share a
 * key, while the expensive scoring runs only on the candidates. Identifiers and embargoed countries are exact keys.
 */
public final class EntryIndex {
    private final List<NameMatcher.Entry> entries;
    private final Map<String, List<NameMatcher.Entry>> byKey = new HashMap<>();
    private final Map<String, List<NameMatcher.Entry>> byIdentifier = new HashMap<>();
    private final Map<String, List<NameMatcher.Entry>> embargoes = new HashMap<>();

    public EntryIndex(List<NameMatcher.Entry> entries) {
        this.entries = List.copyOf(entries);
        for (NameMatcher.Entry e : entries) {
            if ("COUNTRY".equals(e.entryType())) {
                for (String c : e.countries()) {
                    embargoes.computeIfAbsent(c, k -> new ArrayList<>()).add(e);
                }
                continue;
            }
            Set<String> keys = new LinkedHashSet<>();
            for (List<String> name : e.nameTokens()) {
                for (String t : name) {
                    keys.addAll(keys(t));
                }
            }
            for (String k : keys) {
                byKey.computeIfAbsent(k, x -> new ArrayList<>()).add(e);
            }
            for (String id : e.identifiers()) {
                byIdentifier.computeIfAbsent(id, x -> new ArrayList<>()).add(e);
            }
        }
    }

    public int size() {
        return entries.size();
    }

    public Set<NameMatcher.Entry> candidates(Collection<String> tokens, Collection<String> identifiers) {
        Set<NameMatcher.Entry> out = new LinkedHashSet<>();
        for (String t : tokens) {
            for (String k : keys(t)) {
                out.addAll(byKey.getOrDefault(k, List.of()));
            }
        }
        for (String id : identifiers) {
            out.addAll(byIdentifier.getOrDefault(id, List.of()));
        }
        return out;
    }

    /** Country-type entries (embargoes) covering any of the given countries. */
    public List<NameMatcher.Entry> embargoed(Collection<String> countries) {
        List<NameMatcher.Entry> out = new ArrayList<>();
        for (String c : countries) {
            out.addAll(embargoes.getOrDefault(c, List.of()));
        }
        return out;
    }

    static List<String> keys(String t) {
        if (t.length() < 2 || NameNormalizer.PARTICLES.contains(t)) {
            return List.of();
        }
        List<String> k = new ArrayList<>(3);
        k.add("p:" + t.substring(0, 2));
        if (t.length() >= 3) {
            char first = t.charAt(0);
            String rest = t.substring(1, Math.min(3, t.length()));
            k.add("v:" + ("aeiouy".indexOf(first) >= 0 ? "*" : String.valueOf(first)) + rest);
        }
        if (t.length() >= 4) {
            k.add("s:" + t.substring(t.length() - 3));
        }
        return k;
    }
}
