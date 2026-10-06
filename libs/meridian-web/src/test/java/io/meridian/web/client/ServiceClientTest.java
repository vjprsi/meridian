package io.meridian.web.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

class ServiceClientTest {
    final JsonMapper json = JsonMapper.builder().build();

    @Test
    void resolvesManifestPortExplicitUrlAndClusterTemplate() {
        MockEnvironment env = new MockEnvironment();
        assertThat(new ServiceClient(json, env).baseUrl("ledger-service")).isEqualTo("http://localhost:9110");

        env.setProperty("meridian.services.url-template", "http://{name}.meridian.svc.cluster.local");
        ServiceClient cluster = new ServiceClient(json, env);
        assertThat(cluster.baseUrl("payment-service")).isEqualTo("http://payment-service.meridian.svc.cluster.local");
        assertThatThrownBy(() -> cluster.baseUrl("nope-service")).isInstanceOf(IllegalArgumentException.class);

        env.setProperty("meridian.services.payment-service.url", "https://payments.example");
        assertThat(new ServiceClient(json, env).baseUrl("payment-service")).isEqualTo("https://payments.example");
    }
}
