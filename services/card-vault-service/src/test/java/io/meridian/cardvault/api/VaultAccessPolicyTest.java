package io.meridian.cardvault.api;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.cardvault.api.VaultAccessPolicy.Operation;
import io.meridian.common.error.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** SEC-11: reveal / vault administration need dedicated scopes and a recent step-up; cards:write is not enough. */
class VaultAccessPolicyTest {
    static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");
    final VaultAccessPolicy policy = new VaultAccessPolicy(false, 300, List.of("2", "mfa"), List.of("mfa", "otp", "hwk"),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    static void token(String scope, Map<String, Object> extra) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256").subject("ops.anna")
                .claim("scope", scope).claim("tenant_id", "0199a000-0000-7000-8000-00000000000a");
        extra.forEach(b::claim);
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(b.build()));
    }

    static Map<String, Object> steppedUp(long secondsAgo) {
        return Map.of("auth_time", NOW.minusSeconds(secondsAgo).getEpochSecond(), "acr", "2", "amr", List.of("pwd", "otp"));
    }

    static void denied(Runnable r, int status, String code) {
        assertThatThrownBy(r::run).isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException a = (ApiException) e;
                    org.assertj.core.api.Assertions.assertThat(a.status()).isEqualTo(status);
                    org.assertj.core.api.Assertions.assertThat(a.code()).isEqualTo(code);
                });
    }

    @Test
    void cardsWriteWildcardOrAdminCannotReveal() {
        for (String scope : new String[] {"cards:write", "cards:*", "meridian:admin", "cards:vault_admin"}) {
            token(scope, steppedUp(10));
            denied(() -> policy.require(Operation.REVEAL), 403, "insufficient_scope");
        }
    }

    @Test
    void revealNeedsTheDedicatedScopeAndARecentStepUp() {
        token("cards:write cards:pan_reveal", Map.of());
        denied(() -> policy.require(Operation.REVEAL), 401, "insufficient_user_authentication");
        token("cards:pan_reveal", steppedUp(600)); // stale authentication
        denied(() -> policy.require(Operation.REVEAL), 401, "insufficient_user_authentication");
        token("cards:pan_reveal", Map.of("auth_time", NOW.minusSeconds(5).getEpochSecond(), "acr", "1",
                "amr", List.of("pwd"))); // recent but password only
        denied(() -> policy.require(Operation.REVEAL), 401, "insufficient_user_authentication");
        token("cards:pan_reveal", steppedUp(30));
        assertThatCode(() -> policy.require(Operation.REVEAL)).doesNotThrowAnyException();
        token("cards:pan_reveal", Map.of("auth_time", NOW.minusSeconds(30).getEpochSecond(), "amr", List.of("hwk")));
        assertThatCode(() -> policy.require(Operation.REVEAL)).doesNotThrowAnyException();
    }

    @Test
    void pinUnblockAndKeyRotationNeedVaultAdminWithStepUpOrAServiceToken() {
        for (Operation op : new Operation[] {Operation.PIN_UNBLOCK, Operation.ROTATE_KEYS}) {
            token("cards:write", steppedUp(10));
            denied(() -> policy.require(op), 403, "insufficient_scope");
            token("cards:vault_admin", Map.of());
            denied(() -> policy.require(op), 401, "insufficient_user_authentication");
            token("cards:vault_admin", steppedUp(10));
            assertThatCode(() -> policy.require(op)).doesNotThrowAnyException();
            token("cards:* meridian:internal", Map.of()); // card-service orchestrating its own authorised flow
            assertThatCode(() -> policy.require(op)).doesNotThrowAnyException();
        }
    }

    @Test
    void lookupVerifyAndPinSetAreForServices() {
        for (Operation op : new Operation[] {Operation.LOOKUP, Operation.VERIFY, Operation.PIN_SET}) {
            token("cards:write", steppedUp(10));
            denied(() -> policy.require(op), 403, "insufficient_scope");
            token("meridian:internal cards:*", Map.of());
            assertThatCode(() -> policy.require(op)).doesNotThrowAnyException();
        }
    }

    @Test
    void serviceTokensCannotRevealWithoutTheScope() {
        token("cards:* meridian:internal", Map.of());
        denied(() -> policy.require(Operation.REVEAL), 403, "insufficient_scope");
    }

    @Test
    void devModeHasNoTokensAndSkipsTheCheck() {
        VaultAccessPolicy dev = new VaultAccessPolicy(true, 300, List.of(), List.of(), Clock.systemUTC());
        assertThatCode(() -> dev.require(Operation.REVEAL)).doesNotThrowAnyException();
    }
}
