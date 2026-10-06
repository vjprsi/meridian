package io.meridian.sanctions.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** String similarity measures in [0, 1] used by the name matcher. */
public final class Similarity {
    private Similarity() {}

    /** Jaro-Winkler similarity (prefix scale 0.1, prefix up to 4 characters). Symmetric, 1.0 for equal strings. */
    public static double jaroWinkler(String a, String b) {
        if (a.equals(b)) {
            return 1.0;
        }
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        double jaro = jaro(a, b);
        int prefix = 0;
        for (int i = 0; i < Math.min(4, Math.min(a.length(), b.length())); i++) {
            if (a.charAt(i) == b.charAt(i)) {
                prefix++;
            } else {
                break;
            }
        }
        return Math.min(1.0, jaro + prefix * 0.1 * (1.0 - jaro));
    }

    static double jaro(String s, String t) {
        // compute on (shorter, longer) so the measure is exactly symmetric
        String a = s.length() <= t.length() ? s : t;
        String b = s.length() <= t.length() ? t : s;
        if (a.length() > b.length() || (a.length() == b.length() && a.compareTo(b) > 0)) {
            String x = a;
            a = b;
            b = x;
        }
        int window = Math.max(0, Math.max(a.length(), b.length()) / 2 - 1);
        boolean[] am = new boolean[a.length()];
        boolean[] bm = new boolean[b.length()];
        int matches = 0;
        for (int i = 0; i < a.length(); i++) {
            int lo = Math.max(0, i - window);
            int hi = Math.min(b.length() - 1, i + window);
            for (int j = lo; j <= hi; j++) {
                if (!bm[j] && a.charAt(i) == b.charAt(j)) {
                    am[i] = true;
                    bm[j] = true;
                    matches++;
                    break;
                }
            }
        }
        if (matches == 0) {
            return 0.0;
        }
        int k = 0;
        int transpositions = 0;
        for (int i = 0; i < a.length(); i++) {
            if (am[i]) {
                while (!bm[k]) {
                    k++;
                }
                if (a.charAt(i) != b.charAt(k)) {
                    transpositions++;
                }
                k++;
            }
        }
        double m = matches;
        return (m / a.length() + m / b.length() + (m - transpositions / 2.0) / m) / 3.0;
    }

    /**
     * Token-set ratio: compares the shared tokens with each side's full sorted token string, so word order and extra
     * tokens on one side matter less. Order-insensitive and symmetric.
     */
    public static double tokenSetRatio(List<String> a, List<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        Set<String> sa = new TreeSet<>(a);
        Set<String> sb = new TreeSet<>(b);
        Set<String> common = new TreeSet<>(sa);
        common.retainAll(sb);
        Set<String> onlyA = new TreeSet<>(sa);
        onlyA.removeAll(sb);
        Set<String> onlyB = new TreeSet<>(sb);
        onlyB.removeAll(sa);
        String t0 = String.join(" ", common);
        String t1 = join(t0, onlyA);
        String t2 = join(t0, onlyB);
        double best = jaroWinkler(t1, t2);
        if (!common.isEmpty()) {
            best = Math.max(best, Math.max(jaroWinkler(t0, t1), jaroWinkler(t0, t2)));
        }
        return best;
    }

    /** Jaro-Winkler on the alphabetically sorted tokens (word-order insensitive). */
    public static double sortedTokenJaroWinkler(List<String> a, List<String> b) {
        List<String> x = new ArrayList<>(a);
        List<String> y = new ArrayList<>(b);
        Collections.sort(x);
        Collections.sort(y);
        return jaroWinkler(String.join(" ", x), String.join(" ", y));
    }

    /**
     * Average best Jaro-Winkler of each token of the shorter side against the longer side (each token used once).
     * Tolerates per-token misspellings and order changes.
     */
    public static double tokenAlignment(List<String> a, List<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        List<String> shorter = a.size() <= b.size() ? a : b;
        List<String> longer = new ArrayList<>(a.size() <= b.size() ? b : a);
        double sum = 0;
        for (String t : shorter) {
            double best = 0;
            int bestIdx = -1;
            for (int i = 0; i < longer.size(); i++) {
                double s = jaroWinkler(t, longer.get(i));
                if (s > best) {
                    best = s;
                    bestIdx = i;
                }
            }
            if (bestIdx >= 0) {
                longer.remove(bestIdx);
            }
            sum += best;
        }
        return sum / shorter.size();
    }

    /** Share of tokens covered: smaller token count / larger token count (distinct tokens). */
    public static double coverage(List<String> a, List<String> b) {
        int x = new LinkedHashSet<>(a).size();
        int y = new LinkedHashSet<>(b).size();
        if (x == 0 || y == 0) {
            return 0.0;
        }
        return (double) Math.min(x, y) / Math.max(x, y);
    }

    private static String join(String head, Set<String> tail) {
        if (tail.isEmpty()) {
            return head;
        }
        String t = String.join(" ", tail);
        return head.isEmpty() ? t : head + " " + t;
    }
}
