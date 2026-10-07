package io.meridian.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.web.MeridianProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SecurityModeGuardTest {

    @Test
    void jwtIsTheDefaultSecurityMode() {
        assertThat(new MeridianProperties(null, null).devMode()).isFalse();
        assertThat(new MeridianProperties("", null).securityMode()).isEqualTo("jwt");
        assertThat(RequiredSecrets.devMode(new MockEnvironment())).isFalse();
    }

    @Test
    void devModeIsRefusedInsideKubernetesUnlessExplicitlyAllowed() {
        MockEnvironment dev = new MockEnvironment().withProperty("meridian.security-mode", "dev");
        Map<String, String> pod = Map.of("KUBERNETES_SERVICE_HOST", "10.0.0.1");
        assertThatThrownBy(() -> SecurityModeGuard.check(dev, pod))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("inside Kubernetes");
        assertThatCode(() -> SecurityModeGuard.check(dev, Map.of())).doesNotThrowAnyException();
        assertThatCode(() -> SecurityModeGuard.check(
                new MockEnvironment().withProperty("meridian.security-mode", "dev")
                        .withProperty(SecurityModeGuard.ALLOW_IN_CLUSTER, "true"), pod)).doesNotThrowAnyException();
        assertThatCode(() -> SecurityModeGuard.check(new MockEnvironment(), pod)).doesNotThrowAnyException();
    }
}
