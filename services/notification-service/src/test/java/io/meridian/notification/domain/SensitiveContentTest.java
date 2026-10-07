package io.meridian.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SensitiveContentTest {

    @Test
    void conventionalSecretNamesAreSensitive() {
        for (String n : new String[] {"otp", "OTP", "code", "otp_code", "verification_code", "passcode", "pin", "new_pin",
                "password", "temporary_password", "cvv", "step_up_otp", "card.pin", "one_time_code"}) {
            assertThat(SensitiveContent.isSensitiveName(n)).as(n).isTrue();
        }
        for (String n : new String[] {"name", "currency_code", "merchant_name", "amount", "last4", "promo_code", "alias",
                "expires_in_minutes", "card_token_id", "token_requestor"}) {
            assertThat(SensitiveContent.isSensitiveName(n)).as(n).isFalse();
        }
    }

    @Test
    void sensitiveVariablesAreRedactedIncludingDeclaredAndNestedOnes() {
        Map<String, Object> vars = Map.of("otp", "123456", "merchant_name", "Shop", "ref", "R-1",
                "card", Map.of("pin", "9876", "last4", "4242"), "sensitive_variables", List.of("ref"));
        Set<String> secret = SensitiveContent.sensitiveVariables(
                List.of("otp", "merchant_name", "ref", "card.pin", "card.last4"), vars);
        assertThat(secret).containsExactlyInAnyOrder("otp", "ref", "card.pin");
        Map<String, Object> redacted = SensitiveContent.redactedVariables(vars, secret);
        String stored = TemplateRenderer.render("{{otp}} {{merchant_name}} {{ref}} {{card.pin}} {{card.last4}}",
                redacted, false, true);
        assertThat(stored).isEqualTo("[redacted:otp] Shop [redacted:ref] [redacted:card.pin] 4242");
        assertThat(SensitiveContent.isRedacted(stored)).isTrue();
        // the caller's map is untouched (the clear rendering uses it)
        assertThat(vars.get("otp")).isEqualTo("123456");
        assertThat(((Map<?, ?>) vars.get("card")).get("pin")).isEqualTo("9876");
    }

    @Test
    void digitCodesInRenderedSecurityMessagesAreRedacted() {
        assertThat(SensitiveContent.redactDigits("Your code is 482913. It expires in 10 minutes."))
                .isEqualTo("Your code is [redacted]. It expires in 10 minutes.");
        assertThat(SensitiveContent.redactDigits("PIN reset code 1234 for wallet")).isEqualTo("PIN reset code [redacted] for wallet");
        assertThat(SensitiveContent.isRedacted("plain text")).isFalse();
        assertThat(SensitiveContent.redactDigits(null)).isNull();
    }
}
