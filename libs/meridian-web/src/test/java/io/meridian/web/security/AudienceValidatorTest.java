package io.meridian.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class AudienceValidatorTest {

    static Jwt token(String azp, List<String> aud) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256").subject("u").claim("azp", azp)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (aud != null) {
            b.audience(aud);
        }
        return b.build();
    }

    @Test
    void tokensMustBeIssuedForTheMeridianApiOrThisService() {
        AudienceValidator v = new AudienceValidator(SecurityProperties.defaults(), "ledger-service");
        assertThat(v.validate(token("meridian-console", List.of("meridian-api", "account"))).hasErrors()).isFalse();
        assertThat(v.validate(token("meridian-console", List.of("ledger-service"))).hasErrors()).isFalse();
        assertThat(v.validate(token("meridian-console", List.of("account"))).hasErrors()).isTrue();
        assertThat(v.validate(token("meridian-console", null)).hasErrors()).isTrue();
        assertThat(v.validate(token("other-app", List.of("other-api"))).getErrors().iterator().next().getErrorCode())
                .isEqualTo("invalid_token");
    }

    @Test
    void aServiceCanRestrictTheClientsItAccepts() {
        AudienceValidator vault = new AudienceValidator(
                new SecurityProperties(null, List.of("meridian-console", "meridian-service"), null), "card-vault-service");
        assertThat(vault.validate(token("meridian-console", List.of("meridian-api"))).hasErrors()).isFalse();
        assertThat(vault.validate(token("meridian-developer-portal", List.of("meridian-api"))).hasErrors()).isTrue();
        assertThat(vault.validate(token(null, List.of("meridian-api"))).hasErrors()).isTrue();
    }

    @Test
    void anEmptyAudienceListDisablesTheAudienceCheck() {
        AudienceValidator off = new AudienceValidator(new SecurityProperties(List.of(), null, null), "x");
        assertThat(off.validate(token("a", null)).hasErrors()).isFalse();
    }
}
