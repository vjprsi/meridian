package io.meridian.openbanking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.openbanking.TestCerts;
import java.security.KeyPair;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SoftwareStatementTest {
    final KeyPair keys = TestCerts.rsa();
    final Instant now = Instant.now();

    Map<String, Object> claims() {
        Map<String, Object> c = new HashMap<>();
        c.put("software_id", "sw-1");
        c.put("org_name", "Acme Fintech Ltd");
        c.put("org_id", "PSDGB-FCA-123456");
        c.put("software_roles", List.of("AISP", "PSP_PI"));
        c.put("software_redirect_uris", List.of("https://acme.example/cb"));
        c.put("iat", now.getEpochSecond());
        c.put("exp", now.getEpochSecond() + 3600);
        return c;
    }

    @Test
    void verifiesSignatureAndMapsClaims() {
        SoftwareStatement s = SoftwareStatement.verify(TestCerts.ssa(keys, claims()), keys.getPublic(), now);
        assertThat(s.softwareId()).isEqualTo("sw-1");
        assertThat(s.clientName()).isEqualTo("Acme Fintech Ltd");
        assertThat(s.competentAuthority()).isEqualTo("GB-FCA");
        assertThat(s.authorisationNumber()).isEqualTo("123456");
        assertThat(s.country()).isEqualTo("GB");
        assertThat(s.roles()).containsExactly("AISP", "PISP");
        assertThat(s.redirectUris()).containsExactly("https://acme.example/cb");
    }

    @Test
    void rejectsWrongKeyTamperingAndExpiry() {
        String jws = TestCerts.ssa(keys, claims());
        assertThatThrownBy(() -> SoftwareStatement.verify(jws, TestCerts.rsa().getPublic(), now))
                .hasMessageContaining("does not verify");
        String[] parts = jws.split("\\.");
        String tampered = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "AA." + parts[2];
        assertThatThrownBy(() -> SoftwareStatement.verify(tampered, keys.getPublic(), now))
                .isInstanceOf(SoftwareStatement.InvalidStatementException.class);
        Map<String, Object> c = claims();
        c.put("exp", now.getEpochSecond() - 3600);
        assertThatThrownBy(() -> SoftwareStatement.verify(TestCerts.ssa(keys, c), keys.getPublic(), now))
                .extracting(e -> ((SoftwareStatement.InvalidStatementException) e).code())
                .isEqualTo("software_statement_expired");
        assertThatThrownBy(() -> SoftwareStatement.verify("garbage", keys.getPublic(), now))
                .extracting(e -> ((SoftwareStatement.InvalidStatementException) e).code())
                .isEqualTo("malformed_software_statement");
    }

    @Test
    void requiresIdentityClaimsAndKnownRoles() {
        Map<String, Object> noId = claims();
        noId.remove("software_id");
        assertThatThrownBy(() -> SoftwareStatement.verify(TestCerts.ssa(keys, noId), keys.getPublic(), now))
                .hasMessageContaining("software_id");
        Map<String, Object> badRole = claims();
        badRole.put("software_roles", List.of("ASPSP"));
        assertThatThrownBy(() -> SoftwareStatement.verify(TestCerts.ssa(keys, badRole), keys.getPublic(), now))
                .hasMessageContaining("Unknown software role");
        Map<String, Object> noOrg = claims();
        noOrg.remove("org_id");
        assertThatThrownBy(() -> SoftwareStatement.verify(TestCerts.ssa(keys, noOrg), keys.getPublic(), now))
                .extracting(e -> ((SoftwareStatement.InvalidStatementException) e).code())
                .isEqualTo("organisation_id_required");
    }
}
