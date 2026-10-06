package io.meridian.openbanking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.openbanking.TestCerts;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class Psd2CertificateTest {

    @Test
    void parsesOrganisationIdentifierRolesAndCompetentAuthority() {
        TestCerts.Issued c = TestCerts.valid("PSDGB-FCA-123456", List.of("PSP_AI", "PSP_PI"), "GB-FCA");
        Psd2Certificate cert = Psd2Certificate.parse(c.pem());
        assertThat(cert.hasPsd2Statement()).isTrue();
        assertThat(cert.organizationIdentifier()).isEqualTo("PSDGB-FCA-123456");
        assertThat(cert.psd2Roles()).containsExactly("PSP_AI", "PSP_PI");
        assertThat(cert.ncaId()).isEqualTo("GB-FCA");
        assertThat(cert.ncaName()).isEqualTo("Financial Conduct Authority");
        assertThat(cert.fingerprintSha256()).hasSize(64).matches("[0-9a-f]+");
        assertThat(cert.subjectDn()).contains("O=Acme Fintech Ltd");
        assertThat(cert.issuerDn()).contains("CN=Meridian Test QTSP");
        assertThat(cert.uncoveredRoles(List.of("AISP", "PISP"))).isEmpty();
        assertThat(cert.uncoveredRoles(List.of("AISP", "CBPII"))).containsExactly("CBPII");
        cert.requirePsd2Statement("QWAC");
        cert.requireValidAt(Instant.now());
    }

    @Test
    void qwacWithoutPsd2StatementIsRejectedButMtlsIsAccepted() {
        Psd2Certificate cert = Psd2Certificate.parse(TestCerts.valid(null, null, null).pem());
        assertThat(cert.hasPsd2Statement()).isFalse();
        assertThat(cert.organizationIdentifier()).isNull();
        assertThatThrownBy(() -> cert.requirePsd2Statement("QWAC"))
                .isInstanceOf(Psd2Certificate.InvalidCertificateException.class)
                .extracting(e -> ((Psd2Certificate.InvalidCertificateException) e).code())
                .isEqualTo("psd2_statement_missing");
        cert.requirePsd2Statement("MTLS_CLIENT");
    }

    @Test
    void validityWindowIsEnforced() {
        Instant now = Instant.now();
        Psd2Certificate expired = Psd2Certificate.parse(TestCerts.issue("PSDGB-FCA-1", List.of("PSP_AI"), "GB-FCA",
                now.minusSeconds(10 * 86_400), now.minusSeconds(86_400)).pem());
        assertThatThrownBy(() -> expired.requireValidAt(now)).hasMessageContaining("expired");
        Psd2Certificate future = Psd2Certificate.parse(TestCerts.issue("PSDGB-FCA-1", List.of("PSP_AI"), "GB-FCA",
                now.plusSeconds(86_400), now.plusSeconds(10 * 86_400)).pem());
        assertThatThrownBy(() -> future.requireValidAt(now)).hasMessageContaining("valid only from");
    }

    @Test
    void malformedPemIsRejected() {
        assertThatThrownBy(() -> Psd2Certificate.parse("-----BEGIN CERTIFICATE-----\nnot base64!\n-----END CERTIFICATE-----"))
                .isInstanceOf(Psd2Certificate.InvalidCertificateException.class);
        assertThatThrownBy(() -> Psd2Certificate.parse("-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----"))
                .isInstanceOf(Psd2Certificate.InvalidCertificateException.class);
        assertThatThrownBy(() -> Psd2Certificate.parse(" ")).isInstanceOf(Psd2Certificate.InvalidCertificateException.class);
    }

    @Test
    void derReaderDecodesOidsAndRejectsTruncatedInput() {
        // 2.5.4.97 = 55 04 61
        assertThat(Der.parse(new byte[] {0x06, 0x03, 0x55, 0x04, 0x61}).oid()).isEqualTo("2.5.4.97");
        // 0.4.0.19495.2 = 04 00 81 98 27 02
        assertThat(Der.parse(new byte[] {0x06, 0x06, 0x04, 0x00, (byte) 0x81, (byte) 0x98, 0x27, 0x02}).oid())
                .isEqualTo("0.4.0.19495.2");
        assertThatThrownBy(() -> Der.parse(new byte[] {0x30, 0x05, 0x01})).isInstanceOf(Der.MalformedException.class);
        assertThatThrownBy(() -> Der.parse(new byte[] {0x30, (byte) 0x80, 0x00, 0x00})).isInstanceOf(Der.MalformedException.class);
    }

    @Test
    void splitsOrganisationIdentifier() {
        assertThat(Psd2Certificate.splitOrganisationId("PSDGB-FCA-123456")).containsExactly("GB", "GB-FCA", "123456");
        assertThat(Psd2Certificate.splitOrganisationId("NTRGB-123")).isNull();
    }
}
