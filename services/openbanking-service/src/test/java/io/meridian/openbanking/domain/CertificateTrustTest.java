package io.meridian.openbanking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.common.error.ApiException;
import io.meridian.openbanking.TestCerts;
import io.meridian.openbanking.application.CertificateTrust;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** SEC-27: eIDAS certificates must chain to a configured QTSP anchor outside dev mode. */
class CertificateTrustTest {
    static final MockEnvironment JWT = new MockEnvironment().withProperty("meridian.security-mode", "jwt");

    @Test
    void certificatesIssuedByTheAnchorAreAcceptedSelfSignedOnesAreNot() {
        String[] ca = TestCerts.caAndLeaf();
        CertificateTrust trust = new CertificateTrust(List.of(ca[0]), "", false, JWT);
        assertThatCode(() -> trust.verify(ca[1], Instant.now())).doesNotThrowAnyException();
        String forged = TestCerts.valid("PSDGB-FCA-123456", List.of("PSP_AI"), "GB-FCA").pem();
        assertThatThrownBy(() -> trust.verify(forged, Instant.now())).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo("certificate_untrusted"));
        String[] otherCa = TestCerts.caAndLeaf();
        assertThatThrownBy(() -> trust.verify(otherCa[1], Instant.now())).isInstanceOf(ApiException.class);
    }

    @Test
    void withoutAnchorsRegistrationIsRefusedOutsideDevMode() {
        String[] ca = TestCerts.caAndLeaf();
        assertThatThrownBy(() -> new CertificateTrust(List.of(), "", false, JWT).verify(ca[1], Instant.now()))
                .isInstanceOf(ApiException.class);
        assertThatCode(() -> new CertificateTrust(List.of(), "", false,
                new MockEnvironment().withProperty("meridian.security-mode", "dev")).verify(ca[1], Instant.now()))
                .doesNotThrowAnyException();
    }
}
