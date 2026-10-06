package io.meridian.payment.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Confirmation-of-Payee name matching. Names are normalised (accents, case, punctuation, honorifics and legal-form
 * suffixes removed) and scored 0..1 as the best of: Jaro-Winkler over the whole name, an order-insensitive token
 * alignment (each token matched to its best counterpart, initials matching a full given name), and the same after
 * sorting tokens. The outcome uses the scheme thresholds: MATCH, CLOSE_MATCH or NO_MATCH.
 */
public final class NameMatcher {
    public enum Outcome { MATCH, CLOSE_MATCH, NO_MATCH }

    public record Result(Outcome outcome, BigDecimal score) {}

    private static final Set<String> NOISE = Set.of("MR", "MRS", "MS", "MISS", "DR", "PROF", "SIR", "MX",
            "LTD", "LIMITED", "PLC", "LLC", "INC", "CORP", "CO", "GMBH", "AG", "SA", "SAS", "BV", "NV", "THE", "AND");

    private NameMatcher() {}

    public static Result match(String provided, String actual, BigDecimal matchThreshold, BigDecimal closeThreshold) {
        double s = score(provided, actual);
        BigDecimal score = BigDecimal.valueOf(s).setScale(4, RoundingMode.HALF_EVEN);
        Outcome o = score.compareTo(matchThreshold) >= 0 ? Outcome.MATCH
                : score.compareTo(closeThreshold) >= 0 ? Outcome.CLOSE_MATCH : Outcome.NO_MATCH;
        return new Result(o, score);
    }

    /** Similarity 0..1 between two names after normalisation. */
    public static double score(String a, String b) {
        List<String> ta = tokens(a);
        List<String> tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) {
            return 0;
        }
        String ja = String.join(" ", ta);
        String jb = String.join(" ", tb);
        if (ja.equals(jb)) {
            return 1.0;
        }
        double whole = jaroWinkler(ja, jb);
        double tokenAligned = Math.min(tokenScore(ta, tb), tokenScore(tb, ta));
        List<String> sa = new ArrayList<>(ta);
        List<String> sb = new ArrayList<>(tb);
        sa.sort(String::compareTo);
        sb.sort(String::compareTo);
        double sorted = jaroWinkler(String.join(" ", sa), String.join(" ", sb));
        return Math.max(whole, Math.max(tokenAligned, sorted));
    }

    /** Upper-case ASCII tokens without accents, punctuation, honorifics or legal forms. */
    public static List<String> tokens(String name) {
        if (name == null) {
            return List.of();
        }
        String n = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        n = n.toUpperCase(Locale.ROOT).replace("&", " AND ").replaceAll("[^A-Z0-9 ]", " ");
        return Arrays.stream(n.trim().split("\\s+")).filter(t -> !t.isEmpty() && !NOISE.contains(t)).toList();
    }

    /** Average of each token's best similarity in {@code other}; a single letter fully matches a token it starts. */
    private static double tokenScore(List<String> tokens, List<String> other) {
        double total = 0;
        for (String t : tokens) {
            double best = 0;
            for (String o : other) {
                double s;
                if (t.length() == 1 || o.length() == 1) {
                    s = t.charAt(0) == o.charAt(0) ? 0.8 : 0;
                } else {
                    s = jaroWinkler(t, o);
                }
                best = Math.max(best, s);
            }
            total += best;
        }
        double coverage = (double) Math.min(tokens.size(), other.size()) / Math.max(tokens.size(), other.size());
        return (total / tokens.size()) * (0.85 + 0.15 * coverage);
    }

    /** Jaro-Winkler similarity (prefix scale 0.1, max prefix 4). */
    public static double jaroWinkler(String s1, String s2) {
        if (s1.equals(s2)) {
            return 1.0;
        }
        int len1 = s1.length();
        int len2 = s2.length();
        if (len1 == 0 || len2 == 0) {
            return 0;
        }
        int window = Math.max(0, Math.max(len1, len2) / 2 - 1);
        boolean[] m1 = new boolean[len1];
        boolean[] m2 = new boolean[len2];
        int matches = 0;
        for (int i = 0; i < len1; i++) {
            int from = Math.max(0, i - window);
            int to = Math.min(i + window + 1, len2);
            for (int j = from; j < to; j++) {
                if (!m2[j] && s1.charAt(i) == s2.charAt(j)) {
                    m1[i] = true;
                    m2[j] = true;
                    matches++;
                    break;
                }
            }
        }
        if (matches == 0) {
            return 0;
        }
        int transpositions = 0;
        int k = 0;
        for (int i = 0; i < len1; i++) {
            if (m1[i]) {
                while (!m2[k]) {
                    k++;
                }
                if (s1.charAt(i) != s2.charAt(k)) {
                    transpositions++;
                }
                k++;
            }
        }
        double m = matches;
        double jaro = (m / len1 + m / len2 + (m - transpositions / 2.0) / m) / 3.0;
        int prefix = 0;
        for (int i = 0; i < Math.min(4, Math.min(len1, len2)); i++) {
            if (s1.charAt(i) == s2.charAt(i)) {
                prefix++;
            } else {
                break;
            }
        }
        return jaro + prefix * 0.1 * (1 - jaro);
    }

    /** Masks a name for display on alias resolution: first letter of each token kept. */
    public static String mask(String name) {
        if (name == null || name.isBlank()) {
            return name;
        }
        StringBuilder out = new StringBuilder();
        for (String t : name.trim().split("\\s+")) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(t.charAt(0)).append("*".repeat(Math.max(1, t.length() - 1)));
        }
        return out.toString();
    }
}
