package io.meridian.disbursement.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Fuzzy comparison of the beneficiary name in a file with the name on the resolved wallet. Names are normalised
 * (case, accents, punctuation, honorifics) and compared as token sets, order-independent: every token of the shorter
 * name must pair with a distinct token of the longer one, either exactly, as an initial ("J" ~ "John"), or with one
 * typo for tokens of 5+ letters. At least one pair must be a full-token match, and a single-token name only matches a
 * single-token name.
 */
public final class NameMatcher {
    private static final Set<String> HONORIFICS = Set.of("mr", "mrs", "ms", "miss", "dr", "prof", "sir", "madam", "hon");

    private NameMatcher() {}

    public static List<String> tokens(String name) {
        if (name == null) {
            return List.of();
        }
        String n = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", " ").strip();
        if (n.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String t : n.split(" ")) {
            if (!HONORIFICS.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    public static boolean matches(String fileName, String walletName) {
        List<String> a = tokens(fileName);
        List<String> b = tokens(walletName);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        List<String> shorter = a.size() <= b.size() ? a : b;
        List<String> longer = a.size() <= b.size() ? b : a;
        if (shorter.size() == 1 && longer.size() > 1) {
            return false;
        }
        boolean[] used = new boolean[longer.size()];
        boolean fullMatch = false;
        // exact matches first so initials and typos do not steal tokens that match exactly
        boolean[] done = new boolean[shorter.size()];
        for (int i = 0; i < shorter.size(); i++) {
            for (int j = 0; j < longer.size(); j++) {
                if (!used[j] && shorter.get(i).equals(longer.get(j))) {
                    used[j] = true;
                    done[i] = true;
                    fullMatch |= shorter.get(i).length() > 1;
                    break;
                }
            }
        }
        for (int i = 0; i < shorter.size(); i++) {
            if (done[i]) {
                continue;
            }
            boolean paired = false;
            for (int j = 0; j < longer.size() && !paired; j++) {
                if (used[j]) {
                    continue;
                }
                String x = shorter.get(i);
                String y = longer.get(j);
                if (initial(x, y) || initial(y, x)) {
                    paired = true;
                } else if (Math.min(x.length(), y.length()) >= 5 && distance(x, y) <= 1) {
                    paired = true;
                    fullMatch = true;
                }
                if (paired) {
                    used[j] = true;
                }
            }
            if (!paired) {
                return false;
            }
        }
        return fullMatch;
    }

    private static boolean initial(String x, String y) {
        return x.length() == 1 && y.length() > 1 && y.charAt(0) == x.charAt(0);
    }

    /** Levenshtein distance. */
    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
