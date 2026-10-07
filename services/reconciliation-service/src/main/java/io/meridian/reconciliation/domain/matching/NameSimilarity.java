package io.meridian.reconciliation.domain.matching;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Counterparty name similarity in [0, 1], robust to the differences seen between bank statements and internal
 * records: case, accents, punctuation, legal-form suffixes (Ltd / Limited / GmbH ...), token order, typos inside a
 * token, initials ("J SMITH" vs "John Smith"), truncation and joined / split words.
 *
 * <p>Score = max(symmetric soft-token match, Levenshtein ratio of the names without spaces). The soft-token match
 * (a symmetric Monge-Elkan) aligns every token with its best counterpart on the other side, weighting tokens by
 * length: equal tokens score 1, an initial against a token starting with it 0.9, Jaro-Winkler of at least 0.85
 * otherwise (a typo), else 0; an extra token on one side therefore lowers the score in proportion to its length.
 */
public final class NameSimilarity {
    private static final Set<String> NOISE = Set.of("LTD", "LIMITED", "GMBH", "AG", "SA", "SAS", "SARL", "BV", "NV",
            "PLC", "INC", "LLC", "LLP", "CORP", "CORPORATION", "CO", "COMPANY", "SRL", "SPA", "OY", "AB", "AS", "KG",
            "OHG", "EV", "THE", "MR", "MRS", "MS", "DR", "AND", "OF", "PTY", "SL", "SE");

    private NameSimilarity() {}

    /** Similarity of two names, or null when either is missing. */
    public static Double score(String a, String b) {
        if (a == null || b == null) {
            return null;
        }
        List<String> ta = tokens(a);
        List<String> tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) {
            return null;
        }
        double soft = (side(ta, tb) + side(tb, ta)) / (length(ta) + length(tb));
        double joined = levenshteinRatio(String.join("", ta), String.join("", tb));
        return Math.min(1.0, Math.max(soft, joined));
    }

    /** Upper-case ASCII tokens without legal forms and honorifics (kept when nothing else is left). */
    public static List<String> tokens(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT)
                .replace("&", " AND ").replaceAll("[^A-Z0-9]+", " ").trim();
        if (n.isEmpty()) {
            return List.of();
        }
        List<String> all = Arrays.asList(n.split(" "));
        List<String> kept = new ArrayList<>();
        for (String t : all) {
            if (!NOISE.contains(t)) {
                kept.add(t);
            }
        }
        return kept.isEmpty() ? all : kept;
    }

    private static double side(List<String> from, List<String> to) {
        double sum = 0;
        for (String a : from) {
            double best = 0;
            for (String b : to) {
                best = Math.max(best, tokenSim(a, b));
                if (best == 1.0) {
                    break;
                }
            }
            sum += best * a.length();
        }
        return sum;
    }

    private static double length(List<String> t) {
        double n = 0;
        for (String s : t) {
            n += s.length();
        }
        return n;
    }

    static double tokenSim(String a, String b) {
        if (a.equals(b)) {
            return 1.0;
        }
        if ((a.length() == 1 && b.length() > 1 && b.charAt(0) == a.charAt(0))
                || (b.length() == 1 && a.length() > 1 && a.charAt(0) == b.charAt(0))) {
            return 0.9;
        }
        if (a.length() < 3 || b.length() < 3) {
            return 0;
        }
        double jw = jaroWinkler(a, b);
        return jw >= 0.85 ? jw : 0;
    }

    /** Jaro-Winkler similarity (prefix scale 0.1, prefix up to 4 characters). */
    public static double jaroWinkler(String s1, String s2) {
        if (s1.equals(s2)) {
            return 1.0;
        }
        int l1 = s1.length();
        int l2 = s2.length();
        if (l1 == 0 || l2 == 0) {
            return 0.0;
        }
        int window = Math.max(0, Math.max(l1, l2) / 2 - 1);
        boolean[] m1 = new boolean[l1];
        boolean[] m2 = new boolean[l2];
        int matches = 0;
        for (int i = 0; i < l1; i++) {
            int from = Math.max(0, i - window);
            int to = Math.min(l2 - 1, i + window);
            for (int j = from; j <= to; j++) {
                if (!m2[j] && s1.charAt(i) == s2.charAt(j)) {
                    m1[i] = true;
                    m2[j] = true;
                    matches++;
                    break;
                }
            }
        }
        if (matches == 0) {
            return 0.0;
        }
        int t = 0;
        int k = 0;
        for (int i = 0; i < l1; i++) {
            if (m1[i]) {
                while (!m2[k]) {
                    k++;
                }
                if (s1.charAt(i) != s2.charAt(k)) {
                    t++;
                }
                k++;
            }
        }
        double m = matches;
        double jaro = (m / l1 + m / l2 + (m - t / 2.0) / m) / 3.0;
        int prefix = 0;
        for (int i = 0; i < Math.min(4, Math.min(l1, l2)); i++) {
            if (s1.charAt(i) == s2.charAt(i)) {
                prefix++;
            } else {
                break;
            }
        }
        return jaro + prefix * 0.1 * (1 - jaro);
    }

    /** 1 - Levenshtein distance / longer length. */
    public static double levenshteinRatio(String a, String b) {
        int max = Math.max(a.length(), b.length());
        if (max == 0) {
            return 1.0;
        }
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
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return 1.0 - (double) prev[b.length()] / max;
    }
}
