package io.meridian.web.security;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

/**
 * Startup guard for secret configuration (SEC-01 / SEC-02).
 *
 * <p>A service lists the properties holding its secrets (HMAC keys, link-signing keys, master keys) under
 * {@code meridian.required-secrets}:
 * <pre>
 * meridian:
 *   required-secrets:
 *     - meridian.routing.callback-secret
 *     - meridian.routing.callback-secrets.*     # every entry of a map, if any
 * </pre>
 * Outside {@code meridian.security-mode=dev} (i.e. in every deployed environment, where the mode is {@code jwt}) the
 * application context refuses to start when a listed secret is missing, shorter than {@value #MIN_LENGTH}
 * characters, or is a known development value ({@code local-dev-*}, {@code dev-*}, {@code *change-me*}, ... - also
 * checked on the base64-decoded form). The check runs as a {@link BeanFactoryPostProcessor}, i.e. before any
 * bean (data source, Kafka, HTTP server) is created. In dev mode the guard is a no-op so the committed,
 * clearly-labelled local defaults keep working.
 */
public final class RequiredSecrets implements BeanFactoryPostProcessor, EnvironmentAware, Ordered {
    /** Property listing the secret property names a service requires. */
    public static final String PROPERTY = "meridian.required-secrets";
    /** Minimum length (characters) of a production secret. */
    public static final int MIN_LENGTH = 32;

    /** Markers of committed development values (case-insensitive). */
    private static final Pattern DEV_VALUE = Pattern.compile(
            "(local-dev|local_dev|dev-only|dev_only|change-?me|changeit|^dev[-_]|[-_.]dev[-_]|meridian-secret"
                    + "|example|placeholder|not-a-secret|insecure|test-secret|^secret$|^password$)",
            Pattern.CASE_INSENSITIVE);

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        enforce(environment);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    /** True when the service runs with {@code meridian.security-mode=dev} (local development and tests only). */
    public static boolean devMode(Environment env) {
        return "dev".equalsIgnoreCase(env.getProperty("meridian.security-mode", "jwt").trim());
    }

    /** Fails with {@link IllegalStateException} listing every problem, unless the service runs in dev mode. */
    public static void enforce(Environment env) {
        if (env == null || devMode(env)) {
            return;
        }
        List<String> problems = violations(env);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start outside meridian.security-mode=dev: "
                    + String.join("; ", problems)
                    + ". Inject the secrets from the secret store (Kubernetes Secret / KMS), never use committed defaults.");
        }
    }

    /** Every problem with the declared secrets (independent of the security mode). */
    public static List<String> violations(Environment env) {
        List<String> out = new ArrayList<>();
        for (String name : declared(env)) {
            if (name.endsWith(".*")) {
                String prefix = name.substring(0, name.length() - 2);
                Map<String, String> entries = env instanceof ConfigurableEnvironment ce
                        ? Binder.get(ce).bind(prefix, Bindable.mapOf(String.class, String.class)).orElse(Map.of())
                        : Map.of();
                entries.forEach((k, v) -> {
                    String p = problem(v);
                    if (p != null) {
                        out.add(prefix + "." + k + " " + p);
                    }
                });
                continue;
            }
            String p = problem(env.getProperty(name));
            if (p != null) {
                out.add(name + " " + p);
            }
        }
        return out;
    }

    /** The secret property names a service declared under {@value #PROPERTY}. */
    public static List<String> declared(Environment env) {
        List<String> names = env instanceof ConfigurableEnvironment ce
                ? Binder.get(ce).bind(PROPERTY, Bindable.listOf(String.class)).orElse(List.of())
                : List.of(env.getProperty(PROPERTY, String[].class, new String[0]));
        return names.stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
    }

    /** Why the value is not acceptable as a production secret, or null when it is. */
    public static String problem(String value) {
        if (value == null || value.isBlank()) {
            return "is not set";
        }
        String v = value.trim();
        if (v.length() < MIN_LENGTH) {
            return "is shorter than " + MIN_LENGTH + " characters";
        }
        if (isDevValue(v)) {
            return "is a committed development default";
        }
        return null;
    }

    /** True for values that look like committed development defaults (also checked base64-decoded). */
    public static boolean isDevValue(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim();
        if (DEV_VALUE.matcher(v).find()) {
            return true;
        }
        String decoded = tryBase64(v);
        return decoded != null && DEV_VALUE.matcher(decoded).find();
    }

    private static String tryBase64(String v) {
        if (!v.matches("[A-Za-z0-9+/_-]+={0,2}") || v.length() % 4 != 0) {
            return null;
        }
        try {
            byte[] raw = v.contains("-") || v.contains("_") ? Base64.getUrlDecoder().decode(v) : Base64.getDecoder().decode(v);
            String s = new String(raw, StandardCharsets.UTF_8);
            // only meaningful when the decoded bytes are mostly printable text
            long printable = s.chars().filter(c -> c >= 0x20 && c < 0x7f).count();
            return printable >= s.length() * 0.9 ? s.toLowerCase(Locale.ROOT) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
