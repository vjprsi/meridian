package io.meridian.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class RequiredSecretsTest {
    static final String STRONG = "q7Vb1oX0nK9rT2yWm4sE8uZc6dH3jL5pAa0Bb1Cc";

    @Test
    void devDefaultsShortAndMissingValuesAreRejected() {
        assertThat(RequiredSecrets.problem(null)).isEqualTo("is not set");
        assertThat(RequiredSecrets.problem(" ")).isEqualTo("is not set");
        assertThat(RequiredSecrets.problem("short-secret")).contains("shorter");
        assertThat(RequiredSecrets.problem("local-dev-rail-callback-secret-0123456789")).contains("development");
        assertThat(RequiredSecrets.problem("change-me-esign-callback-secret-0123456789")).contains("development");
        assertThat(RequiredSecrets.problem("dev-callback-secret-padded-to-thirty-two")).contains("development");
        assertThat(RequiredSecrets.problem("dev-only-audit-download-signing-key")).contains("development");
        // the formerly committed card-vault master key and webhook key, base64 of "meridian-dev-..." / "dev-only-..."
        assertThat(RequiredSecrets.problem("bWVyaWRpYW4tZGV2LXZhdWx0LW1hc3Rlci1rZXktMDE=")).contains("development");
        assertThat(RequiredSecrets.problem("ZGV2LW9ubHktd2ViaG9vay1rZXktMzItYnl0ZXMhISE=")).contains("development");
        assertThat(RequiredSecrets.problem(STRONG)).isNull();
        assertThat(RequiredSecrets.problem("3q2+7wABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhs=")).isNull(); // random base64
    }

    @Test
    void jwtModeRefusesMissingOrDefaultSecretsListingEveryProblem() {
        StandardEnvironment env = env(Map.of(
                "meridian.security-mode", "jwt",
                "meridian.required-secrets", "meridian.a.secret,meridian.b.secret,meridian.c.secret,meridian.rails.*",
                "meridian.b.secret", "local-dev-rail-callback-secret",
                "meridian.c.secret", STRONG,
                "meridian.rails.SEPA", "change-me-but-long-enough-to-pass-length",
                "meridian.rails.FPS", STRONG));
        assertThatThrownBy(() -> RequiredSecrets.enforce(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("meridian.a.secret is not set")
                .hasMessageContaining("meridian.b.secret is shorter")
                .hasMessageContaining("meridian.rails.SEPA is a committed development default")
                .hasMessageNotContaining("meridian.c.secret")
                .hasMessageNotContaining("FPS");
    }

    @Test
    void missingSecurityModeMeansJwt() {
        StandardEnvironment env = env(Map.of("meridian.required-secrets", "meridian.a.secret"));
        assertThatThrownBy(() -> RequiredSecrets.enforce(env)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void devModeKeepsLocalDefaultsWorking() {
        StandardEnvironment env = env(Map.of("meridian.security-mode", "dev",
                "meridian.required-secrets", "meridian.a.secret", "meridian.a.secret", "local-dev-secret"));
        RequiredSecrets.enforce(env);
    }

    @Test
    void contextFailsBeforeBeansAreCreatedInJwtModeAndStartsWithRealSecrets() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(RequiredSecrets.class, RequiredSecrets::new)
                .withPropertyValues("meridian.security-mode=jwt",
                        "meridian.required-secrets[0]=meridian.x.callback-secret");
        runner.withPropertyValues("meridian.x.callback-secret=local-dev-x-callback-secret-0123456789")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().hasMessageContaining("meridian.x.callback-secret"));
        runner.withPropertyValues("meridian.x.callback-secret=" + STRONG)
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    private static StandardEnvironment env(Map<String, Object> props) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        return env;
    }
}
