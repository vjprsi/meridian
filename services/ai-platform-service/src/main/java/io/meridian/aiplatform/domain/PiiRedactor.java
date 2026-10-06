package io.meridian.aiplatform.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Redacts personal data before text leaves the bank (LLM gateway) or is stored in the copilot audit. Built-in
 * entities are matched in a fixed order with validation that keeps false positives low (Luhn for card numbers, ISO
 * 13616 mod-97 for IBANs); tenant patterns run afterwards on a time-limited input so a pathological regex cannot hang
 * a request. Each match becomes a {@code [ENTITY]} token and is counted.
 */
public final class PiiRedactor {
    public enum Entity { EMAIL, IBAN, PAN, NATIONAL_ID, PHONE, ACCOUNT_NUMBER, IP_ADDRESS }

    public static final int MAX_PATTERN_LENGTH = 200;
    public static final long PATTERN_BUDGET_MS = 50;
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern IBAN = Pattern.compile("\\b[A-Z]{2}\\d{2}(?: ?[A-Z0-9]{4}){2,7}(?: ?[A-Z0-9]{1,4})?\\b");
    private static final Pattern PAN = Pattern.compile("\\b\\d(?:[ -]?\\d){12,18}\\b");
    private static final Pattern NATIONAL_ID = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b|\\b[A-Z]{2}\\d{6}[A-D]\\b");
    private static final Pattern PHONE = Pattern.compile("(?<![\\w])\\+\\d{1,3}[ .-]?\\(?\\d{1,4}\\)?(?:[ .-]?\\d{2,4}){2,4}\\b");
    private static final Pattern ACCOUNT = Pattern.compile("\\b\\d{8,17}\\b");
    private static final Pattern IPV4 = Pattern.compile("\\b(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}\\b");
    private static final Pattern NESTED_QUANTIFIER = Pattern.compile("\\([^)]*[+*][^)]*\\)[+*{]");

    private PiiRedactor() {}

    /** A tenant pattern. */
    public record CustomPattern(String name, Pattern pattern) {}

    public record Result(String text, Map<String, Integer> counts) {
        public int total() {
            return counts.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    /** Compiles tenant patterns; rejects invalid, over-long, empty-matching and nested-quantifier patterns. */
    public static List<CustomPattern> compile(List<Map<String, Object>> raw, List<String> errors) {
        List<CustomPattern> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (int i = 0; i < raw.size(); i++) {
            Object name = raw.get(i).get("name");
            Object pattern = raw.get(i).get("pattern");
            if (!(name instanceof String n) || !n.matches("[A-Z][A-Z0-9_]{0,39}")) {
                errors.add("redaction_patterns[" + i + "].name must be an upper-case identifier");
                continue;
            }
            if (!(pattern instanceof String p) || p.isEmpty() || p.length() > MAX_PATTERN_LENGTH) {
                errors.add("redaction_patterns[" + i + "].pattern must be 1-" + MAX_PATTERN_LENGTH + " characters");
                continue;
            }
            if (NESTED_QUANTIFIER.matcher(p).find()) {
                errors.add("redaction_patterns[" + i + "].pattern uses nested quantifiers (catastrophic backtracking risk)");
                continue;
            }
            try {
                Pattern c = Pattern.compile(p);
                if (c.matcher("").matches()) {
                    errors.add("redaction_patterns[" + i + "].pattern must not match empty text");
                    continue;
                }
                out.add(new CustomPattern(n, c));
            } catch (PatternSyntaxException e) {
                errors.add("redaction_patterns[" + i + "].pattern is not a valid regular expression: " + e.getDescription());
            }
        }
        return out;
    }

    public static Set<Entity> entities(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return EnumSet.allOf(Entity.class);
        }
        EnumSet<Entity> out = EnumSet.noneOf(Entity.class);
        names.forEach(n -> out.add(Entity.valueOf(n)));
        return out;
    }

    public static Result redact(String text, Set<Entity> entities, List<CustomPattern> custom) {
        return redact(text, entities, custom, PATTERN_BUDGET_MS * 1_000_000);
    }

    /** As {@link #redact(String, Set, List)} with an explicit time budget per tenant pattern (nanoseconds). */
    static Result redact(String text, Set<Entity> entities, List<CustomPattern> custom, long budgetNanos) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (text == null || text.isEmpty()) {
            return new Result(text, counts);
        }
        String s = text;
        if (entities.contains(Entity.EMAIL)) {
            s = replace(s, EMAIL, "EMAIL", counts, m -> true);
        }
        if (entities.contains(Entity.IBAN)) {
            s = replace(s, IBAN, "IBAN", counts, PiiRedactor::validIban);
        }
        // Phone numbers (always with an international prefix) go before card numbers so a +country number is never
        // mistaken for a PAN that happens to pass Luhn.
        if (entities.contains(Entity.PHONE)) {
            s = replace(s, PHONE, "PHONE", counts, m -> m.replaceAll("\\D", "").length() >= 8);
        }
        if (entities.contains(Entity.PAN)) {
            s = replace(s, PAN, "PAN", counts, m -> luhn(m.replaceAll("[ -]", "")));
        }
        if (entities.contains(Entity.NATIONAL_ID)) {
            s = replace(s, NATIONAL_ID, "NATIONAL_ID", counts, m -> true);
        }
        if (entities.contains(Entity.IP_ADDRESS)) {
            s = replace(s, IPV4, "IP_ADDRESS", counts, m -> true);
        }
        if (entities.contains(Entity.ACCOUNT_NUMBER)) {
            s = replace(s, ACCOUNT, "ACCOUNT_NUMBER", counts, m -> true);
        }
        for (CustomPattern c : custom) {
            s = replaceBounded(s, c, counts, budgetNanos);
        }
        return new Result(s, counts);
    }

    private static String replace(String s, Pattern p, String token, Map<String, Integer> counts,
                                  java.util.function.Predicate<String> valid) {
        Matcher m = p.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            if (valid.test(m.group())) {
                m.appendReplacement(sb, "[" + token + "]");
                counts.merge(token, 1, Integer::sum);
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String replaceBounded(String s, CustomPattern c, Map<String, Integer> counts, long budgetNanos) {
        long deadline = System.nanoTime() + budgetNanos;
        try {
            Matcher m = c.pattern().matcher(new Deadline(s, deadline));
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(sb, "[" + c.name() + "]");
                counts.merge(c.name(), 1, Integer::sum);
            }
            m.appendTail(sb);
            return sb.toString();
        } catch (Deadline.Exceeded e) {
            // Fail closed: when a tenant pattern cannot finish, the whole text is withheld rather than leaked.
            counts.merge(c.name() + "_TIMEOUT", 1, Integer::sum);
            return "[REDACTED: pattern " + c.name() + " timed out]";
        }
    }

    static boolean luhn(String digits) {
        if (digits.length() < 13 || digits.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }

    static boolean validIban(String raw) {
        String iban = raw.replace(" ", "");
        if (iban.length() < 15 || iban.length() > 34) {
            return false;
        }
        String r = iban.substring(4) + iban.substring(0, 4);
        int mod = 0;
        for (char ch : r.toCharArray()) {
            int v = Character.isDigit(ch) ? ch - '0' : ch - 'A' + 10;
            mod = v > 9 ? (mod * 100 + v) % 97 : (mod * 10 + v) % 97;
        }
        return mod == 1;
    }

    /** A CharSequence that aborts regex matching once a deadline passes. */
    static final class Deadline implements CharSequence {
        static final class Exceeded extends RuntimeException {
            Exceeded() {
                super("pattern time budget exceeded", null, false, false);
            }
        }

        private final CharSequence inner;
        private final long deadline;

        Deadline(CharSequence inner, long deadline) {
            this.inner = inner;
            this.deadline = deadline;
        }

        @Override
        public char charAt(int index) {
            if (System.nanoTime() > deadline) {
                throw new Exceeded();
            }
            return inner.charAt(index);
        }

        @Override
        public int length() {
            return inner.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new Deadline(inner.subSequence(start, end), deadline);
        }

        @Override
        public String toString() {
            return inner.toString();
        }
    }
}
