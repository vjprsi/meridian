package io.meridian.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class HeaderPolicyTest {

    @Test
    void jwtModeDropsInternalIdentityHeadersDevModeForwardsThem() {
        HeaderPolicy jwt = new HeaderPolicy("jwt", List.of());
        HeaderPolicy dev = new HeaderPolicy("dev", List.of());
        for (String h : List.of("X-Tenant-Id", "x-user", "X-Roles", "X-MFA-Verified", "X-Customer-Id",
                "X-On-Behalf-Of-Customer", "X-Meridian-Service", "X-Auth-Time", "X-Acr", "X-Permissions",
                "X-Caller-Service", "X-Agent-Id", "X-Business-Customer-Id", "X-Business-User-Id")) {
            assertThat(jwt.forward(h)).as(h).isFalse();
            assertThat(dev.forward(h)).as(h).isTrue();
        }
        assertThat(jwt.forward("Authorization")).isTrue();
        assertThat(jwt.forward("Idempotency-Key")).isTrue();
        assertThat(jwt.forward("X-Rail-Signature")).isTrue();
        assertThat(jwt.forward("X-Agent-Device-Proof")).isTrue();
        assertThat(jwt.forward("X-Applicant-Token")).isTrue();
        assertThat(new HeaderPolicy(null, null).devMode()).isFalse();
    }

    @Test
    void clientForwardingHeadersAreNeverCopied() {
        for (HeaderPolicy p : List.of(new HeaderPolicy("jwt", List.of()), new HeaderPolicy("dev", List.of()))) {
            for (String h : List.of("X-Forwarded-For", "x-forwarded-proto", "X-Forwarded-Host", "Forwarded",
                    "X-Real-IP", "Host", "Content-Length")) {
                assertThat(p.forward(h)).as(h).isFalse();
            }
        }
    }

    @Test
    void clientAddressIsTheSocketPeerUnlessThePeerIsATrustedProxy() {
        HeaderPolicy none = new HeaderPolicy("jwt", List.of());
        assertThat(none.clientAddress("203.0.113.9", List.of("1.2.3.4"))).isEqualTo("203.0.113.9");

        HeaderPolicy lb = new HeaderPolicy("jwt", List.of("10.0.0.0/8", "192.168.1.5"));
        // spoofed left-most entries are ignored: the right-most untrusted hop is the client
        assertThat(lb.clientAddress("10.1.2.3", List.of("6.6.6.6, 198.51.100.7"))).isEqualTo("198.51.100.7");
        assertThat(lb.clientAddress("10.1.2.3", List.of("6.6.6.6", "198.51.100.7, 10.9.9.9"))).isEqualTo("198.51.100.7");
        assertThat(lb.clientAddress("192.168.1.5", List.of())).isEqualTo("192.168.1.5");
        assertThat(lb.clientAddress("192.168.1.6", List.of("6.6.6.6"))).isEqualTo("192.168.1.6");
        assertThat(lb.clientAddress("10.1.2.3", List.of("not-an-ip"))).isEqualTo("10.1.2.3");
        assertThat(new HeaderPolicy("jwt", List.of("2001:db8::/32")).clientAddress("2001:db8::1", List.of("2001:dead::5")))
                .isEqualTo("2001:dead::5");
        assertThatThrownBy(() -> new HeaderPolicy("jwt", List.of("example.com"))).isInstanceOf(IllegalArgumentException.class);
    }
}
