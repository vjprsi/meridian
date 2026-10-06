package io.meridian.customer.domain;

import java.text.Normalizer.Form;
import java.util.Locale;
import java.util.regex.Pattern;

/** Canonical forms of names, identifiers, phones and emails for matching (never for display). */
public final class Normalizer {
    private Normalizer() {}

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9 ]");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    public static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{6,14}$");
    public static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]{2,}$");

    /** Lower case, accents removed, punctuation dropped, whitespace collapsed: "  José  O'Neil " -> "jose oneil". */
    public static String name(String s) {
        if (s == null) {
            return "";
        }
        String folded = MARKS.matcher(java.text.Normalizer.normalize(s, Form.NFD)).replaceAll("");
        String lower = folded.toLowerCase(Locale.ROOT).replace('-', ' ');
        return SPACES.matcher(NON_ALNUM.matcher(lower).replaceAll("")).replaceAll(" ").trim();
    }

    /** Upper case, separators removed: "ab 12-34.5" -> "AB12345". */
    public static String identifier(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT).replaceAll("[\\s\\-./]", "");
    }

    /** E.164 with separators removed; returns the input stripped when it is not a phone number. */
    public static String phone(String s) {
        if (s == null) {
            return null;
        }
        String p = s.replaceAll("[\\s\\-().]", "");
        if (p.startsWith("00")) {
            p = "+" + p.substring(2);
        }
        return p;
    }

    public static String email(String s) {
        return s == null ? null : s.trim().toLowerCase(Locale.ROOT);
    }

    /** Escapes LIKE wildcards so user input is matched literally. */
    public static String likeLiteral(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
