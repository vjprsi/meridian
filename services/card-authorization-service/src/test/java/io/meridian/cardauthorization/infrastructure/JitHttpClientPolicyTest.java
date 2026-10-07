package io.meridian.cardauthorization.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.cardauthorization.application.Ports;
import io.meridian.cardauthorization.config.AuthProperties;
import io.meridian.web.outbound.OutboundUrlPolicy;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** SEC-18: JIT webhooks only reach public https endpoints outside dev mode. */
class JitHttpClientPolicyTest {
    final JsonMapper json = JsonMapper.builder().build();
    final JitHttpClient strict = new JitHttpClient(json,
            new AuthProperties("q7Vb1oX0nK9rT2yWm4sE8uZc6dH3jL5pAa0Bb1Cc", null, null, null, null, null),
            new OutboundUrlPolicy(OutboundUrlPolicy.Settings.strict(), false));

    @Test
    void internalOrPlainHttpWebhooksAreNeverCalled() {
        for (String url : new String[] {"http://127.0.0.1:9/jit", "https://169.254.169.254/latest/meta-data/",
                "https://10.0.0.1/jit", "https://card-service/v1/cards"}) {
            Ports.Jit.Response r = strict.call(url, 500, json.createObjectNode().put("amount", "1.00"));
            assertThat(r.result()).as(url).isEqualTo("ERROR");
            assertThat(r.httpStatus()).isNull();
        }
    }
}
