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

    @Test
    void forwardsDevModeCallerHeadersFromTheInboundRequest() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var seen = new java.util.concurrent.atomic.AtomicReference<String>();
        server.createContext("/v1/x", ex -> {
            seen.set(ex.getRequestHeaders().getFirst("X-Roles") + "|" + ex.getRequestHeaders().getFirst("X-MFA-Verified"));
            byte[] body = "{}".getBytes();
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            MockEnvironment env = new MockEnvironment();
            env.setProperty("meridian.services.ledger-service.url", "http://127.0.0.1:" + server.getAddress().getPort());
            var inbound = new org.springframework.mock.web.MockHttpServletRequest();
            inbound.addHeader("X-Roles", "PAYMENTS_APPROVER,OPS");
            inbound.addHeader("X-MFA-Verified", "true");
            org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                    new org.springframework.web.context.request.ServletRequestAttributes(inbound));
            new ServiceClient(json, env).get("ledger-service", "/v1/x");
            assertThat(seen.get()).isEqualTo("PAYMENTS_APPROVER,OPS|true");
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
            server.stop(0);
        }
    }
}
