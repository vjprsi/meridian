package io.meridian.common.text;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * SEC-30: tenant-supplied regular expressions run on user / public input in JVMs shared by every tenant. Matching is
 * bounded: inputs longer than {@value #MAX_INPUT} characters never match, and a match that runs longer than
 * {@value #TIMEOUT_MS} ms is abandoned (treated as no match) through a deadline-checking {@link CharSequence}.
 * {@link #problem(String)} rejects invalid, over-long and nested-quantifier patterns when they are saved.
 */
public final class SafeRegex {
    public static final int MAX_INPUT = 4096;
    public static final int MAX_PATTERN = 500;
    public static final long TIMEOUT_MS = 100;
    /** A quantified group that itself contains a quantifier, e.g. (a+)+, (\w*)*, (x|y+){2,}: catastrophic backtracking. */
    private static final Pattern NESTED = Pattern.compile("\\((?:[^()\\\\]|\\\\.)*[+*}](?:[^()\\\\]|\\\\.)*\\)\\s*[+*{]");
    private static final Map<String, Pattern> CACHE = new ConcurrentHashMap<>();

    private SafeRegex() {}

    /** Why the pattern may not be stored, or empty when it is acceptable. */
    public static Optional<String> problem(String regex) {
        if (regex == null || regex.isEmpty()) {
            return Optional.of("pattern is empty");
        }
        if (regex.length() > MAX_PATTERN) {
            return Optional.of("pattern is longer than " + MAX_PATTERN + " characters");
        }
        try {
            Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            return Optional.of("pattern is not a valid regular expression");
        }
        if (NESTED.matcher(regex).find()) {
            return Optional.of("pattern nests quantifiers (catastrophic backtracking)");
        }
        return Optional.empty();
    }

    /** Full match within the limits; invalid patterns, long inputs and timeouts do not match. */
    public static boolean matches(String regex, String input) {
        return run(regex, 0, input, true);
    }

    public static boolean matches(String regex, int flags, String input) {
        return run(regex, flags, input, true);
    }

    /** Partial match ({@code find}) within the limits. */
    public static boolean find(String regex, int flags, String input) {
        return run(regex, flags, input, false);
    }

    private static boolean run(String regex, int flags, String input, boolean full) {
        if (regex == null || input == null || input.length() > MAX_INPUT || regex.length() > MAX_PATTERN) {
            return false;
        }
        Pattern p;
        try {
            p = pattern(regex, flags);
        } catch (PatternSyntaxException e) {
            return false;
        }
        Deadline text = new Deadline(input, System.nanoTime() + TIMEOUT_MS * 1_000_000);
        try {
            return full ? p.matcher(text).matches() : p.matcher(text).find();
        } catch (TimedOut e) {
            return false;
        }
    }

    private static Pattern pattern(String regex, int flags) {
        String key = flags + "/" + regex;
        Pattern p = CACHE.get(key);
        if (p == null) {
            p = Pattern.compile(regex, flags);
            if (CACHE.size() < 2000) {
                CACHE.put(key, p);
            }
        }
        return p;
    }

    private static final class TimedOut extends RuntimeException {
        TimedOut() {
            super(null, null, false, false);
        }
    }

    /** Throws once the deadline passes; the regex engine reads every character through charAt. */
    private record Deadline(CharSequence s, long deadline) implements CharSequence {
        @Override
        public char charAt(int i) {
            if (System.nanoTime() > deadline) {
                throw new TimedOut();
            }
            return s.charAt(i);
        }

        @Override
        public int length() {
            return s.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new Deadline(s.subSequence(start, end), deadline);
        }

        @Override
        public String toString() {
            return s.toString();
        }
    }
}
