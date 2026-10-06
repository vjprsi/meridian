package io.meridian.cardtokenization.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.cardtokenization.domain.ProvisioningDecision.Path;
import java.nio.charset.StandardCharsets;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class TokenizationDomainTest {

    // ------------------------------------------------------------------ provisioning decision

    @Test
    void greenYellowRedPaths() {
        assertThat(ProvisioningDecision.assess("ACTIVE", true, 5, 4, 0, 10).path()).isEqualTo(Path.GREEN);
        assertThat(ProvisioningDecision.assess("ACTIVE", true, 3, 5, 0, 10).path()).isEqualTo(Path.YELLOW);
        assertThat(ProvisioningDecision.assess("ACTIVE", true, null, null, 0, 10)).isEqualTo(
                new ProvisioningDecision.Assessment(Path.YELLOW, "STEP_UP_REQUIRED"));
        assertThat(ProvisioningDecision.assess("ACTIVE", true, 1, 5, 0, 10).reason()).isEqualTo("HIGH_RISK_SCORE");
        assertThat(ProvisioningDecision.assess("ACTIVE", true, 5, 0, 0, 10).path()).isEqualTo(Path.RED);
        assertThat(ProvisioningDecision.assess("FROZEN", true, 5, 5, 0, 10).reason()).isEqualTo("CARD_NOT_ACTIVE");
        assertThat(ProvisioningDecision.assess("ACTIVE", false, 5, 5, 0, 10).reason()).isEqualTo("EXPIRY_MISMATCH");
        assertThat(ProvisioningDecision.assess("ACTIVE", true, 5, 5, 10, 10).reason()).isEqualTo("TOKEN_LIMIT_REACHED");
        assertThat(ProvisioningDecision.assess("ACTIVE", true, 5, 5, 9, 10).path()).isEqualTo(Path.GREEN);
    }

    @Property
    void nonActiveCardIsNeverGreen(@ForAll("cardStatuses") String status, @ForAll boolean expiry,
                                   @ForAll @IntRange(min = 0, max = 6) int wallet,
                                   @ForAll @IntRange(min = 0, max = 6) int device,
                                   @ForAll @IntRange(min = 0, max = 20) int live) {
        Path p = ProvisioningDecision.assess(status, expiry, wallet, device, live, 10).path();
        if (!"ACTIVE".equals(status)) {
            assertThat(p).isEqualTo(Path.RED);
        }
        if (p == Path.GREEN) {
            assertThat(wallet).isGreaterThanOrEqualTo(4);
            assertThat(device).isGreaterThanOrEqualTo(4);
            assertThat(expiry).isTrue();
            assertThat(live).isLessThan(10);
        }
    }

    @Provide
    Arbitrary<String> cardStatuses() {
        return Arbitraries.of("CREATED", "ACTIVE", "FROZEN", "BLOCKED", "LOST", "STOLEN", "EXPIRED", "CLOSED", "REPLACED");
    }

    // ------------------------------------------------------------------ lifecycle

    @Test
    void lifecycleTransitions() {
        assertThat(TokenLifecycle.allowed("REQUESTED", "ACTIVE")).isTrue();
        assertThat(TokenLifecycle.allowed("PENDING_VERIFICATION", "ACTIVE")).isTrue();
        assertThat(TokenLifecycle.allowed("PENDING_VERIFICATION", "SUSPENDED")).isFalse();
        assertThat(TokenLifecycle.allowed("ACTIVE", "SUSPENDED")).isTrue();
        assertThat(TokenLifecycle.allowed("SUSPENDED", "ACTIVE")).isTrue();
        assertThat(TokenLifecycle.allowed("ACTIVE", "ACTIVE")).isFalse();
        assertThat(TokenLifecycle.allowed("DEACTIVATED", "ACTIVE")).isFalse();
        assertThat(TokenLifecycle.allowed("DECLINED", "ACTIVE")).isFalse();
        assertThat(TokenLifecycle.terminal("DEACTIVATED")).isTrue();
        assertThat(TokenLifecycle.terminal("DECLINED")).isTrue();
        assertThat(TokenLifecycle.terminal("SUSPENDED")).isFalse();
        assertThat(TokenLifecycle.eventFor("SUSPENDED", "ACTIVE")).isEqualTo("RESUMED");
        assertThat(TokenLifecycle.eventFor("PENDING_VERIFICATION", "ACTIVE")).isEqualTo("ACTIVATED");
    }

    @Test
    void cardStatusAndNetworkEventMapping() {
        assertThat(TokenLifecycle.targetForCardStatus("FROZEN")).isEqualTo("SUSPENDED");
        assertThat(TokenLifecycle.targetForCardStatus("ACTIVE")).isEqualTo("ACTIVE");
        assertThat(TokenLifecycle.targetForCardStatus("STOLEN")).isEqualTo("DEACTIVATED");
        assertThat(TokenLifecycle.targetForCardStatus("CREATED")).isNull();
        assertThat(TokenLifecycle.targetForNetworkEvent("RESUMED")).isEqualTo("ACTIVE");
        assertThat(TokenLifecycle.targetForNetworkEvent("PAN_UPDATED")).isNull();
        assertThatThrownBy(() -> TokenLifecycle.targetForNetworkEvent("STEP_UP_SENT"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Property
    void terminalStatesHaveNoExit(@ForAll("tokenStatuses") String to) {
        assertThat(TokenLifecycle.allowed("DEACTIVATED", to)).isFalse();
        assertThat(TokenLifecycle.allowed("DECLINED", to)).isFalse();
    }

    @Provide
    Arbitrary<String> tokenStatuses() {
        return Arbitraries.of("REQUESTED", "PENDING_VERIFICATION", "ACTIVE", "SUSPENDED", "DEACTIVATED", "DECLINED");
    }

    // ------------------------------------------------------------------ crypto

    @Test
    void otpHashingIsSaltedAndVerifiable() {
        String otp = Crypto.randomDigits(6);
        assertThat(otp).matches("\\d{6}");
        String h = Crypto.otpHash("k", "token-1", otp);
        assertThat(h).hasSize(64).isNotEqualTo(Crypto.otpHash("k", "token-2", otp));
        assertThat(Crypto.otpMatches("k", "token-1", otp, h)).isTrue();
        assertThat(Crypto.otpMatches("k", "token-1", " " + otp + " ", h)).isTrue();
        assertThat(Crypto.otpMatches("k", "token-2", otp, h)).isFalse();
        assertThat(Crypto.otpMatches("other", "token-1", otp, h)).isFalse();
        assertThat(Crypto.otpMatches("k", "token-1", null, h)).isFalse();
    }

    @Test
    void signaturesAndSealedPayloads() {
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        String sig = Crypto.hmacHex("secret", body);
        assertThat(Crypto.verifySignature("secret", body, sig)).isTrue();
        assertThat(Crypto.verifySignature("secret", body, sig.toUpperCase())).isTrue();
        assertThat(Crypto.verifySignature("secret", "{\"a\":2}".getBytes(StandardCharsets.UTF_8), sig)).isFalse();
        assertThat(Crypto.verifySignature("secret", body, null)).isFalse();
        String sealed = Crypto.seal("key", "{\"card_id\":\"x\"}");
        assertThat(sealed).isNotEqualTo(Crypto.seal("key", "{\"card_id\":\"x\"}"));
        assertThat(Crypto.open("key", sealed)).isEqualTo("{\"card_id\":\"x\"}");
        assertThatThrownBy(() -> Crypto.open("wrong", sealed)).isInstanceOf(IllegalStateException.class);
    }
}
