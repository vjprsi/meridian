package io.meridian.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.web.security.RequiredSecrets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/** GAP-14: the contact lookup key and the customer secret key are guarded like every other service secret. */
class CustomerSecretsTest {
    static final String STRONG_A = "q7Vb1oX0nK9rT2yWm4sE8uZc6dH3jL5pAa0Bb1Cc";
    static final String STRONG_B = "Zr8Lw2Qp5Nc1Xv7Hs3Kd9Fg4Jt6Ym0Ue2Ri5Oa7S";

    @Test
    void bothSecretsAreDeclared() throws Exception {
        assertThat(RequiredSecrets.declared(env(Map.of())))
                .contains("meridian.contact-lookup.key", "meridian.customer.secret-key");
    }

    @Test
    void committedDefaultsOnlyWorkInDevMode() throws Exception {
        // jwt mode (every deployed environment) with the committed application.yml defaults: refuses to start
        assertThatThrownBy(() -> RequiredSecrets.enforce(env(Map.of("meridian.security-mode", "jwt"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("meridian.contact-lookup.key is a committed development default")
                .hasMessageContaining("meridian.customer.secret-key");
        // dev mode keeps the local stack working
        RequiredSecrets.enforce(env(Map.of("meridian.security-mode", "dev")));
        // secrets from the secret store (MERIDIAN_CONTACT_LOOKUP_KEY / MERIDIAN_CUSTOMER_SECRET_KEY via Helm)
        RequiredSecrets.enforce(env(Map.of("meridian.security-mode", "jwt",
                "meridian.contact-lookup.key", STRONG_A, "meridian.customer.secret-key", STRONG_B)));
    }

    private static StandardEnvironment env(Map<String, Object> overrides) throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        // the service's own application.yml, resolved without the process environment (no MERIDIAN_* variables)
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(ps -> env.getPropertySources().addLast(ps));
        env.getPropertySources().addFirst(new MapPropertySource("overrides", overrides));
        return env;
    }
}
