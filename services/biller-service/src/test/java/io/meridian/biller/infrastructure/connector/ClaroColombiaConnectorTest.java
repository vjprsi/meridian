package io.meridian.biller.infrastructure.connector;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.biller.application.BillerGateway.Outcome;
import io.meridian.biller.application.BillerGateway.Result;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ClaroColombiaConnectorTest {
    private final JsonMapper json = JsonMapper.builder().build();

    private ClaroColombiaConnector connector(StubTransport transport) {
        return new ClaroColombiaConnector(transport, new CircuitBreakerRegistry(),
                new BillerSecrets(new StandardEnvironment()), json);
    }

    private ObjectNode biller(String aggregator, int maxAttempts) {
        ObjectNode cfg = json.createObjectNode();
        cfg.put("base_url", "https://api.claro.com.co");
        cfg.put("distributor_id", "MERIDIAN-CO");
        cfg.putObject("retry").put("max_attempts", maxAttempts).put("backoff_ms", 0);
        ObjectNode b = json.createObjectNode();
        b.put("id", "22222222-2222-2222-2222-222222222222");
        b.put("biller_code", "CLARO");
        b.put("integration_type", "AGGREGATOR");
        b.put("aggregator_name", aggregator);
        b.put("category", "AIRTIME");
        b.set("connection_config", cfg);
        return b;
    }

    private ObjectNode topup() {
        return json.createObjectNode().put("id", "top-1").put("target_msisdn", "+57 300 1234567")
                .put("amount", "5000").put("currency", "COP");
    }

    @Test
    void supportsClaroAggregatorBillersOnly() {
        ClaroColombiaConnector c = connector(new StubTransport());
        assertThat(c.supports(biller("CLARO_CO", 2))).isTrue();
        assertThat(c.supports(biller("claro", 2))).isTrue();
        assertThat(c.supports(biller("WIRECARD", 2))).isFalse();
        ObjectNode apiType = biller("CLARO_CO", 2);
        apiType.put("integration_type", "API");
        assertThat(c.supports(apiType)).isFalse();
    }

    @Test
    void mapsAnApprovedRecharge() {
        StubTransport t = new StubTransport().respond(200, "{\"status\":\"APPROVED\",\"transactionId\":\"CLR-123\"}");
        Result r = connector(t).topup(biller("CLARO_CO", 2), topup(), 1);
        assertThat(r.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(r.providerReference()).isEqualTo("CLR-123");
        // request shape: digits-only msisdn, client reference, idempotency key
        StubTransport.Call call = t.calls.getFirst();
        assertThat(call.url()).isEqualTo("https://api.claro.com.co/airtime/v1/recharges");
        assertThat(call.body()).contains("\"msisdn\":\"573001234567\"").contains("\"clientReference\":\"top-1\"")
                .contains("\"distributorId\":\"MERIDIAN-CO\"");
        assertThat(call.headers()).containsEntry("Idempotency-Key", "top-1:1");
    }

    @Test
    void mapsADeclinedRecharge() {
        StubTransport t = new StubTransport().respond(200, "{\"status\":\"DECLINED\",\"message\":\"Sin saldo\"}");
        Result r = connector(t).topup(biller("CLARO_CO", 2), topup(), 1);
        assertThat(r.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(r.message()).isEqualTo("Sin saldo");
    }

    @Test
    void operatorOutageTimesOut() {
        StubTransport t = new StubTransport().respond(502, "bad gateway").respond(502, "bad gateway");
        Result r = connector(t).topup(biller("CLARO_CO", 2), topup(), 1);
        assertThat(r.outcome()).isEqualTo(Outcome.TIMEOUT);
        assertThat(t.calls).hasSize(2);
    }
}
