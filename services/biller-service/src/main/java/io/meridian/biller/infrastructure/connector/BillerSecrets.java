package io.meridian.biller.infrastructure.connector;

import java.util.Locale;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Resolves the credential a connector's {@code auth} block references. The database only ever holds a reference, never
 * a literal secret. A {@code secret_ref} is resolved, in order:
 * <ul>
 *   <li>{@code env:NAME} &rarr; the Spring property / environment variable {@code NAME};</li>
 *   <li>otherwise &rarr; the Spring property {@code meridian.biller.secrets.<ref>} (non-alphanumerics folded to
 *       {@code .}), then the environment variable {@code <REF>} (non-alphanumerics folded to {@code _}, upper-cased).</li>
 * </ul>
 * This maps cleanly onto a Vault / secrets-manager agent that renders secrets into the environment or config. An
 * inline {@code value} is honoured only in {@code meridian.security-mode=dev} (local sandboxes and tests).
 */
@Component
public class BillerSecrets {
    private final Environment env;
    private final boolean dev;

    public BillerSecrets(Environment env) {
        this.env = env;
        this.dev = "dev".equalsIgnoreCase(env.getProperty("meridian.security-mode", "jwt").trim());
    }

    /** @param auth the {@code connection_config.auth} block; returns the resolved secret or {@code null}. */
    public String resolve(JsonNode auth) {
        if (auth == null || auth.isMissingNode() || auth.isNull()) {
            return null;
        }
        String ref = text(auth, "secret_ref");
        if (ref != null && !ref.isBlank()) {
            String v = lookup(ref.trim());
            if (v != null) {
                return v;
            }
        }
        if (dev) {
            return text(auth, "value");
        }
        return null;
    }

    private String lookup(String ref) {
        if (ref.regionMatches(true, 0, "env:", 0, 4)) {
            String name = ref.substring(4).trim();
            String v = env.getProperty(name);
            return v != null ? v : System.getenv(name);
        }
        String prop = "meridian.biller.secrets." + fold(ref, '.');
        String v = env.getProperty(prop);
        if (v != null) {
            return v;
        }
        String var = fold(ref, '_').toUpperCase(Locale.ROOT);
        v = env.getProperty(var);
        return v != null ? v : System.getenv(var);
    }

    private static String fold(String ref, char sep) {
        String s = ref.replaceAll("[^A-Za-z0-9]+", String.valueOf(sep));
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == sep) {
            start++;
        }
        while (end > start && s.charAt(end - 1) == sep) {
            end--;
        }
        return s.substring(start, end);
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asString();
    }
}
