package io.meridian.notification.domain;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Sensitive message content (SEC-10): one-time passcodes, PINs, temporary passwords. Such values are rendered into
 * the message only at send time and are never persisted, returned by the API or published in events / audit /
 * webhooks; the stored notification carries a redacted body ({@code [redacted:otp]}).
 *
 * <p>A template variable is sensitive when its name (last path segment) is a well-known secret name
 * ({@code otp}, {@code code}, {@code pin}, {@code passcode}, {@code password}, {@code cvv}, {@code *_otp},
 * {@code *_code} ...) or when the caller lists it in the reserved variable {@value #DECLARATION} (a list of names).
 * Already-rendered {@code SECURITY} messages (e.g. {@code POST /v1/notifications} with a code in the text) are
 * protected by redacting every run of 4-10 digits in the stored copy.
 */
public final class SensitiveContent {
    /** Reserved variable: names of additional sensitive variables. */
    public static final String DECLARATION = "sensitive_variables";
    /** Prefix of a redaction placeholder in a stored body. */
    public static final String MARKER = "[redacted";

    private static final Pattern SENSITIVE_NAME = Pattern.compile(
            "(otp|otp_?code|one_?time_?(pass)?code|verification_?code|security_?code|auth_?code|activation_?code"
                    + "|confirmation_?code|code|passcode|pass_?code|pin|pin_?code|new_?pin|password|temp(orary)?_?password"
                    + "|cvv|cvv2|cvc|secret|.+_otp|.+_pin|.+_passcode)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DIGIT_RUN = Pattern.compile("(?<![0-9])[0-9]{4,10}(?![0-9])");
    private static final Pattern ANY_MARKER = Pattern.compile("\\[redacted(:[A-Za-z0-9_.-]+)?]");

    private SensitiveContent() {}

    /** True if the variable path names a secret by convention. */
    public static boolean isSensitiveName(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String last = path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return SENSITIVE_NAME.matcher(last).matches();
    }

    /** The sensitive variables among those a template uses: by convention or declared by the caller. */
    public static Set<String> sensitiveVariables(Collection<String> used, Map<String, ?> vars) {
        Set<String> declared = new LinkedHashSet<>();
        if (vars != null && vars.get(DECLARATION) instanceof Collection<?> c) {
            c.forEach(x -> declared.add(String.valueOf(x)));
        }
        Set<String> out = new LinkedHashSet<>();
        for (String name : used) {
            if (isSensitiveName(name) || declared.contains(name)) {
                out.add(name);
            }
        }
        return out;
    }

    /** A copy of the variables where each sensitive path is replaced by its redaction placeholder. */
    public static Map<String, Object> redactedVariables(Map<String, ?> vars, Set<String> sensitive) {
        Map<String, Object> copy = deepCopy(vars);
        for (String path : sensitive) {
            String[] parts = path.split("\\.");
            Map<String, Object> cur = copy;
            for (int i = 0; i < parts.length - 1 && cur != null; i++) {
                Object next = cur.get(parts[i]);
                cur = next instanceof Map<?, ?> ? cast(next) : null;
            }
            if (cur != null && cur.containsKey(parts[parts.length - 1])) {
                cur.put(parts[parts.length - 1], "[redacted:" + path + "]");
            }
        }
        return copy;
    }

    /** Redacts runs of 4-10 digits (codes, PINs) in an already-rendered security message. */
    public static String redactDigits(String text) {
        return text == null ? null : DIGIT_RUN.matcher(text).replaceAll("[redacted]");
    }

    /** True if a stored text contains a redaction placeholder (the clear text exists only in memory). */
    public static boolean isRedacted(String text) {
        return text != null && ANY_MARKER.matcher(text).find();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object o) {
        return (Map<String, Object>) o;
    }

    private static Map<String, Object> deepCopy(Map<String, ?> vars) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (vars == null) {
            return out;
        }
        vars.forEach((k, v) -> out.put(k, v instanceof Map<?, ?> m ? deepCopy(cast(m)) : v));
        return out;
    }
}
