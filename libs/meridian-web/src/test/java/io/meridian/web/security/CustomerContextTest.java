package io.meridian.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.web.security.CustomerContext.CustomerPrincipal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class CustomerContextTest {
    static final String CUSTOMER = "0199a000-0000-7000-8000-0000000c0001";

    private static Jwt.Builder jwt() {
        return Jwt.withTokenValue("t").header("alg", "RS256").subject("kc-user").issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60)).claim("scope", "openid channels:customer")
                .claim("customer_id", CUSTOMER);
    }

    @Test
    void readsAcrAmrSidAndSubjectFromTheToken() {
        CustomerPrincipal p = CustomerContext.fromJwt(jwt().claim("auth_time", 1_800_000_000L).claim("acr", "2")
                .claim("amr", List.of("pwd", "otp")).claim("sid", "abc-123").build()).orElseThrow();
        assertThat(p.acr()).isEqualTo("2");
        assertThat(p.amr()).containsExactly("pwd", "otp");
        assertThat(p.sessionId()).isEqualTo("abc-123");
        assertThat(p.subject()).isEqualTo("kc-user");
        assertThat(p.authTime()).isEqualTo(Instant.ofEpochSecond(1_800_000_000L));
    }

    @Test
    void missingClaimsAreEmpty() {
        CustomerPrincipal p = CustomerContext.fromJwt(jwt().build()).orElseThrow();
        assertThat(p.amr()).isEmpty();
        assertThat(p.sessionId()).isNull();
        assertThat(p.acr()).isNull();
    }

    @Test
    void devHeadersCarryAmrAndSession() {
        CustomerPrincipal p = CustomerContext.fromDevHeaders(CUSTOMER, "1800000000", "2", "pwd, hwk", "s-1")
                .orElseThrow();
        assertThat(p.amr()).containsExactly("pwd", "hwk");
        assertThat(p.sessionId()).isEqualTo("s-1");
        assertThat(p.subject()).isNull();
        assertThat(CustomerContext.fromDevHeaders(CUSTOMER, null, null, null, "x".repeat(65)).orElseThrow()
                .sessionId()).isNull();
        assertThat(CustomerContext.fromDevHeaders(CUSTOMER, null, " ", null, null).orElseThrow().acr()).isNull();
    }
}
