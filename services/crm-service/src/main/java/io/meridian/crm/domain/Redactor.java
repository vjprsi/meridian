package io.meridian.crm.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** PII redaction and guardrails applied to copilot prompts before they reach any model, plus audit hashing. */
public final class Redactor {
    private Redactor() {}

    private static final Map<String, Pattern> RULES = new LinkedHashMap<>();

    static {
        RULES.put("EMAIL", Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"));
        RULES.put("IBAN", Pattern.compile("\\b[A-Z]{2}\\d{2}[A-Z0-9]{11,30}\\b"));
        RULES.put("CARD_NUMBER", Pattern.compile("\\b(?:\\d[ -]?){13,19}\\b"));
        RULES.put("PHONE", Pattern.compile("\\+?\\d[\\d ()-]{7,}\\d"));
        RULES.put("NUMBER", Pattern.compile("\\b\\d{6,}\\b"));
    }

    private static final List<String> INJECTION = List.of("ignore previous instructions", "ignore all previous",
            "disregard the above", "system prompt", "you are now", "jailbreak");

    public record Result(String text, Map<String, Integer> redactions, List<String> flags) {
        public boolean redacted() {
            return !redactions.isEmpty();
        }
    }

    public static Result redact(String text) {
        if (text == null) {
            return new Result(null, Map.of(), List.of());
        }
        String out = text;
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map.Entry<String, Pattern> r : RULES.entrySet()) {
            Matcher m = r.getValue().matcher(out);
            int n = 0;
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                n++;
                m.appendReplacement(sb, "[" + r.getKey() + "]");
            }
            m.appendTail(sb);
            out = sb.toString();
            if (n > 0) {
                counts.put(r.getKey(), n);
            }
        }
        List<String> flags = new ArrayList<>();
        String lower = text.toLowerCase(Locale.ROOT);
        if (INJECTION.stream().anyMatch(lower::contains)) {
            flags.add("PROMPT_INJECTION_SUSPECTED");
        }
        return new Result(out, counts, flags);
    }

    public static String sha256(String s) {
        if (s == null) {
            return null;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
