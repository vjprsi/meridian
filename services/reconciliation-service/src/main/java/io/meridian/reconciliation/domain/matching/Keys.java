package io.meridian.reconciliation.domain.matching;

import io.meridian.common.text.SafeRegex;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reference normalisation: upper case, alphanumerics only, so "INV-2026/00123", "inv 2026 00123" and
 * "INV202600123" compare equal. Tokens from free text are the regex extractions (tenant patterns, bounded by
 * {@link SafeRegex}) and n-grams of up to {@value #MAX_NGRAM} consecutive alphanumeric words; only tokens of at
 * least {@value #MIN_TOKEN} characters containing a digit are kept (words like "PAYMENT" never act as references).
 */
public final class Keys {
    public static final int MIN_TOKEN = 5;
    public static final int MAX_TOKEN = 40;
    public static final int MAX_NGRAM = 4;
    private static final int MAX_TOKENS = 200;
    private static final Pattern NON_ALNUM = Pattern.compile("[^A-Z0-9]+");

    private Keys() {}

    /** Normalised key, or null when nothing is left. */
    public static String norm(String s) {
        if (s == null) {
            return null;
        }
        String n = NON_ALNUM.matcher(s.toUpperCase(Locale.ROOT)).replaceAll("");
        return n.isEmpty() ? null : n;
    }

    /** Reference tokens of a text: pattern extractions and alphanumeric n-grams (normalised, with a digit). */
    public static Set<String> tokens(String text, List<String> patterns) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        for (String p : patterns) {
            for (String v : SafeRegex.extract(p, 0, text, 10)) {
                String n = norm(v);
                // a pattern match is evidence by itself: long letter-only ids (BATCH-ABCDEFGH) count too
                if (usable(n) || (n != null && n.length() >= 10 && n.length() <= MAX_TOKEN && out.size() < MAX_TOKENS)) {
                    out.add(n);
                }
            }
        }
        String upper = text.toUpperCase(Locale.ROOT);
        List<String> words = new ArrayList<>();
        for (String w : NON_ALNUM.split(upper)) {
            if (!w.isEmpty()) {
                words.add(w);
            }
        }
        for (int i = 0; i < words.size() && out.size() < MAX_TOKENS; i++) {
            StringBuilder sb = new StringBuilder();
            for (int n = 0; n < MAX_NGRAM && i + n < words.size(); n++) {
                sb.append(words.get(i + n));
                if (sb.length() > MAX_TOKEN) {
                    break;
                }
                add(out, sb.toString());
            }
        }
        return out;
    }

    /** True when the normalised value is usable as a reference token. */
    public static boolean usable(String token) {
        if (token == null || token.length() < MIN_TOKEN || token.length() > MAX_TOKEN) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            if (Character.isDigit(token.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static void add(Set<String> out, String token) {
        if (usable(token) && out.size() < MAX_TOKENS) {
            out.add(token);
        }
    }
}
