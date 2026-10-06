package io.meridian.channel.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Keyword SMS commands. The first word is the keyword (case-insensitive); the remaining words are positional
 * arguments named by the command syntax, e.g. syntax {@code SEND amount msisdn pin} on body "send 500 +2547.. 1234"
 * gives {amount=500, msisdn=+2547.., pin=1234}. Any argument named {@code pin} is masked before the body is logged.
 */
public final class SmsParser {
    private SmsParser() {}

    public record Parsed(String keyword, List<String> args) {}

    public static Parsed parse(String body) {
        String trimmed = body == null ? "" : body.trim();
        if (trimmed.isEmpty()) {
            return new Parsed("", List.of());
        }
        String[] parts = trimmed.split("\\s+");
        List<String> args = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            args.add(parts[i]);
        }
        return new Parsed(parts[0].toUpperCase(Locale.ROOT), args);
    }

    /** Argument names of a syntax (words after the keyword, lower-cased). */
    public static List<String> argNames(String syntax) {
        if (syntax == null || syntax.isBlank()) {
            return List.of();
        }
        String[] parts = syntax.trim().split("\\s+");
        List<String> names = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            names.add(parts[i].toLowerCase(Locale.ROOT).replaceAll("[<>\\[\\]]", ""));
        }
        return names;
    }

    /** Named arguments; missing positions are absent, extra words are ignored. */
    public static Map<String, String> bind(Parsed p, String syntax) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> names = argNames(syntax);
        for (int i = 0; i < names.size() && i < p.args().size(); i++) {
            out.put(names.get(i), p.args().get(i));
        }
        return out;
    }

    /** The body with every argument at a "pin" position (or any bare 4-6 digit last word for PIN commands) masked. */
    public static String mask(String body, String syntax, boolean requiresPin) {
        if (body == null) {
            return null;
        }
        String[] parts = body.trim().split("\\s+");
        List<String> names = argNames(syntax);
        boolean masked = false;
        for (int i = 0; i < names.size() && i + 1 < parts.length; i++) {
            if (names.get(i).equals("pin")) {
                parts[i + 1] = UssdEngine.MASK;
                masked = true;
            }
        }
        if (!masked && requiresPin && parts.length > 1 && parts[parts.length - 1].matches("\\d{4,6}")) {
            parts[parts.length - 1] = UssdEngine.MASK;
        }
        return String.join(" ", parts);
    }
}
