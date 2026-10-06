package io.meridian.webhook.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.common.error.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WebhookDomainTest {

    @Test
    void retryScheduleSpansAboutSeventyTwoHoursThenAbandons() {
        OffsetDateTime created = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        OffsetDateTime now = created;
        int attempts = 1;
        Optional<OffsetDateTime> next;
        while ((next = RetrySchedule.nextAttempt(attempts, created, now)).isPresent()) {
            assertThat(next.get()).isAfter(now);
            now = next.get();
            attempts++;
        }
        assertThat(attempts).isEqualTo(RetrySchedule.maxAttempts());
        assertThat(Duration.between(created, now)).isBetween(Duration.ofHours(70), Duration.ofHours(73));
        assertThat(RetrySchedule.nextAttempt(1, created, created.plusHours(80))).isEmpty();
    }

    @Test
    void eventTypePatterns() {
        assertThat(EventTypePattern.matches("payments.*", "payments.payment.settled")).isTrue();
        assertThat(EventTypePattern.matches("payments.*", "paymentsx.y")).isFalse();
        assertThat(EventTypePattern.matches("*", "anything.at.all")).isTrue();
        assertThat(EventTypePattern.matches("a.b", "a.b.c")).isFalse();
        assertThat(EventTypePattern.isValid("Payments.**")).isFalse();
        assertThat(EventTypePattern.isConcreteType("payments.*")).isFalse();
    }

    @Test
    void signatureRoundTripAndTolerance() {
        String header = WebhookSignature.header(List.of("s1", "s0"), 1000, "{}");
        assertThat(WebhookSignature.verify("s1", header, "{}", 1010, 300)).isTrue();
        assertThat(WebhookSignature.verify("s0", header, "{}", 1010, 300)).isTrue();
        assertThat(WebhookSignature.verify("s1", header, "{ }", 1010, 300)).isFalse();
        assertThat(WebhookSignature.verify("s1", header, "{}", 2000, 300)).isFalse();
    }

    @Test
    void secretsRotateWithGracePeriod() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        SigningSecrets s = SigningSecrets.fresh();
        SigningSecrets r = s.rotate(now);
        assertThat(r.previous()).isEqualTo(s.current());
        assertThat(r.active(now.plusSeconds(60))).containsExactly(r.current(), s.current());
        assertThat(r.active(now.plus(Duration.ofHours(25)))).containsExactly(r.current());
    }

    @Test
    void payloadFilterSubset() {
        PayloadFilter f = PayloadFilter.parse("data.currency == \"USD\" && amount != 0 && flag == true");
        Map<String, String> payload = Map.of("currency", "USD", "amount", "10.00", "flag", "true");
        assertThat(f.test(p -> payload.get(String.join(".", p)))).isTrue();
        Map<String, String> other = Map.of("currency", "EUR", "amount", "10.00", "flag", "true");
        assertThat(f.test(p -> other.get(String.join(".", p)))).isFalse();
        assertThatThrownBy(() -> PayloadFilter.parse("amount > 5")).isInstanceOf(ApiException.class);
    }

    @Test
    void urlPolicy() {
        WebhookRules.validateUrl("https://hooks.example.com/x", false);
        assertThatThrownBy(() -> WebhookRules.validateUrl("http://localhost:8080/x", false)).isInstanceOf(ApiException.class);
        WebhookRules.validateUrl("http://localhost:8080/x", true);
        assertThatThrownBy(() -> WebhookRules.validateUrl("http://example.com/x", true)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> WebhookRules.validateUrl("https://user:pw@example.com/x", false)).isInstanceOf(ApiException.class);
    }
}
