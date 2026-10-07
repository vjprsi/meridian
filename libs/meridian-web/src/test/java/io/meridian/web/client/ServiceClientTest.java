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

    /**
     * A customer's bearer token is never relayed: the call carries the service's client-credentials token, the tenant
     * and customer:&lt;id&gt; as the acting user; dev-mode role headers are dropped. Without service credentials the
     * call is refused instead of going out with the customer's token.
     */
    @Test
    void customerRequestsCallDownstreamWithTheServiceIdentityNeverTheCustomerToken() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var seen = new java.util.concurrent.atomic.AtomicReference<String>();
        server.createContext("/token", ex -> {
            byte[] body = "{\"access_token\":\"svc-token\",\"expires_in\":300}".getBytes();
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/v1/x", ex -> {
            var h = ex.getRequestHeaders();
            seen.set(h.getFirst("Authorization") + "|" + h.getFirst("X-Tenant-Id") + "|" + h.getFirst("X-User") + "|"
                    + h.getFirst("X-Roles") + "|" + h.getFirst("X-On-Behalf-Of-Customer"));
            byte[] body = "{}".getBytes();
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        java.util.UUID tenant = java.util.UUID.randomUUID();
        java.util.UUID customer = java.util.UUID.randomUUID();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            var inbound = new org.springframework.mock.web.MockHttpServletRequest();
            inbound.addHeader("Authorization", "Bearer customer-token");
            inbound.addHeader("X-Roles", "PAYMENTS_APPROVER");
            org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                    new org.springframework.web.context.request.ServletRequestAttributes(inbound));
            var principal = new io.meridian.web.security.CustomerContext.CustomerPrincipal(customer, null, null);
            io.meridian.common.tenancy.TenantContext.set(tenant, principal.principalName());
            io.meridian.web.security.CustomerContext.set(principal);

            MockEnvironment env = new MockEnvironment();
            env.setProperty("meridian.services.ledger-service.url", base);
            assertThatThrownBy(() -> new ServiceClient(json, env).get("ledger-service", "/v1/x"))
                    .isInstanceOfSatisfying(io.meridian.common.error.ApiException.class,
                            e -> assertThat(e.code()).isEqualTo("service_identity_not_configured"));
            assertThat(seen.get()).isNull();

            env.setProperty("meridian.service-auth.token-uri", base + "/token");
            env.setProperty("meridian.service-auth.client-id", "meridian-service");
            env.setProperty("meridian.service-auth.client-secret", "s");
            new ServiceClient(json, env).get("ledger-service", "/v1/x");
            assertThat(seen.get()).isEqualTo("Bearer svc-token|" + tenant + "|customer:" + customer + "|null|" + customer);
        } finally {
            io.meridian.web.security.CustomerContext.clear();
            io.meridian.common.tenancy.TenantContext.clear();
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
            server.stop(0);
        }
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
