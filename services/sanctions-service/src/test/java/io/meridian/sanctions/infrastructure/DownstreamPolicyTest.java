package io.meridian.sanctions.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.common.error.ApiException;
import io.meridian.web.outbound.OutboundHttp;
import io.meridian.web.outbound.OutboundUrlPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** SEC-39: publisher list downloads go through the shared outbound policy. */
class DownstreamPolicyTest {

    @Test
    void privateMetadataAndPlainHttpSourcesAreRefusedBeforeAnyConnection() throws Exception {
        try (OutboundHttp http = new OutboundHttp(OutboundUrlPolicy.fromEnvironment(
                new MockEnvironment().withProperty("meridian.security-mode", "jwt")))) {
            Downstream d = new Downstream(null, http);
            for (String url : new String[] {"https://169.254.169.254/latest/meta-data/", "https://127.0.0.1/list.xml",
                    "http://lists.example.org/sdn.xml", "https://10.1.2.3/sdn.xml"}) {
                assertThatThrownBy(() -> d.download(url)).as(url).isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("source_url_not_allowed"));
            }
        }
    }
}
