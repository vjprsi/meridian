package io.meridian.biller.infrastructure.connector;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.biller.application.BillerGateway.Inquiry;
import io.meridian.biller.application.BillerGateway.Outcome;
import io.meridian.biller.application.BillerGateway.Result;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class GenericRestBillerConnectorTest {
    private final JsonMapper json = JsonMapper.builder().build();

    private GenericRestBillerConnector connector(StubTransport transport) {
        return new GenericRestBillerConnector(transport, new CircuitBreakerRegistry(),
                new BillerSecrets(new StandardEnvironment()), json);
    }

    /** base_url + zero backoff so retries do not slow the test; small breaker threshold for the breaker test. */
    private ObjectNode biller(String code, int maxAttempts, int breakerThreshold) {
        ObjectNode cfg = json.createObjectNode();
        cfg.put("base_url", "https://api.partner.example.com");
        cfg.putObject("retry").put("max_attempts", maxAttempts).put("backoff_ms", 0);
        cfg.putObject("circuit_breaker").put("failure_threshold", breakerThreshold).put("cooldown_ms", 60_000);
        ObjectNode b = json.createObjectNode();
        b.put("id", "11111111-1111-1111-1111-111111111111");
        b.put("biller_code", code);
        b.put("integration_type", "API");
        b.put("category", "ELECTRICITY");
        b.set("connection_config", cfg);
        return b;
    }

    private ObjectNode payment(String ref) {
        return json.createObjectNode().put("id", "pay-1").put("account_reference", ref)
                .put("amount", "1234.50").put("currency", "KES");
    }

    @Test
    void supportsOnlyBillersWithAConnectionConfig() {
        GenericRestBillerConnector c = connector(new StubTransport());
        assertThat(c.supports(biller("KPLC", 3, 5))).isTrue();
        ObjectNode noCfg = json.createObjectNode().put("integration_type", "API").put("biller_code", "X");
        assertThat(c.supports(noCfg)).isFalse();
        ObjectNode fileType = biller("KPLC", 3, 5);
        fileType.put("integration_type", "FILE");
        assertThat(c.supports(fileType)).isFalse();
    }

    @Test
    void mapsASuccessfulPayment() {
        StubTransport t = new StubTransport().respond(200, "{\"responseCode\":\"00\",\"providerReference\":\"PR-9\",\"receiptNumber\":\"RC-1\"}");
        Result r = connector(t).pay(biller("KPLC", 3, 5), payment("12345678"), 1);
        assertThat(r.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(r.providerReference()).isEqualTo("PR-9");
        assertThat(r.receiptNumber()).isEqualTo("RC-1");
        // the request carried a stable idempotency key and the mapped amount
        assertThat(t.calls).hasSize(1);
        assertThat(t.calls.getFirst().headers()).containsEntry("Idempotency-Key", "pay-1:1");
        assertThat(t.calls.getFirst().body()).contains("\"amount\":\"1234.50\"").contains("\"accountReference\":\"12345678\"");
    }

    @Test
    void mapsAProviderDeclineToRejected() {
        StubTransport t = new StubTransport().respond(200, "{\"responseCode\":\"05\",\"message\":\"Account closed\"}");
        Result r = connector(t).pay(biller("KPLC", 3, 5), payment("12345678"), 1);
        assertThat(r.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(r.message()).isEqualTo("Account closed");
    }

    @Test
    void retriesOnServerErrorThenSucceeds() {
        StubTransport t = new StubTransport()
                .respond(503, "upstream down")
                .respond(200, "{\"responseCode\":\"00\",\"providerReference\":\"PR-OK\"}");
        Result r = connector(t).pay(biller("KPLC", 3, 5), payment("12345678"), 1);
        assertThat(r.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(t.calls).hasSize(2);
    }

    @Test
    void returnsTimeoutWhenServerErrorsAreExhausted() {
        StubTransport t = new StubTransport().respond(500, "x").respond(500, "x").respond(500, "x");
        Result r = connector(t).pay(biller("KPLC", 3, 5), payment("12345678"), 1);
        assertThat(r.outcome()).isEqualTo(Outcome.TIMEOUT);
        assertThat(t.calls).hasSize(3);
    }

    @Test
    void retryableTransportFailureTimesOutButBlockedUrlIsRejected() {
        StubTransport timeout = new StubTransport().fail(true).fail(true);
        assertThat(connector(timeout).pay(biller("KPLC", 2, 5), payment("1"), 1).outcome()).isEqualTo(Outcome.TIMEOUT);

        StubTransport blocked = new StubTransport().fail(false);
        Result r = connector(blocked).pay(biller("KPLC", 2, 5), payment("1"), 1);
        assertThat(r.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(blocked.calls).hasSize(1); // not retried
    }

    @Test
    void circuitBreakerShortCircuitsAfterRepeatedFailures() {
        StubTransport t = new StubTransport().respond(500, "x").respond(500, "x");
        // one attempt per call, breaker trips after 2 failures
        GenericRestBillerConnector c = connector(t);
        ObjectNode biller = biller("ACME", 1, 2);
        assertThat(c.pay(biller, payment("1"), 1).outcome()).isEqualTo(Outcome.TIMEOUT);
        assertThat(c.pay(biller, payment("1"), 2).outcome()).isEqualTo(Outcome.TIMEOUT);
        // breaker now OPEN: the third call is short-circuited without hitting the transport
        Result r = c.pay(biller, payment("1"), 3);
        assertThat(r.outcome()).isEqualTo(Outcome.TIMEOUT);
        assertThat(r.message()).contains("circuit open");
        assertThat(t.calls).hasSize(2);
    }

    @Test
    void mapsAFoundInquiry() {
        StubTransport t = new StubTransport().respond(200,
                "{\"responseCode\":\"00\",\"customerName\":\"Grace N\",\"amountDue\":\"250.00\",\"dueDate\":\"2026-02-01\",\"reference\":\"INQ-9\"}");
        Inquiry inq = connector(t).inquire(biller("KPLC", 3, 5), "12345678");
        assertThat(inq.found()).isTrue();
        assertThat(inq.customerName()).isEqualTo("Grace N");
        assertThat(inq.amountDue()).isEqualByComparingTo("250.00");
        assertThat(inq.inquiryReference()).isEqualTo("INQ-9");
    }

    @Test
    void unavailableBillerYieldsIssuerInoperativeInquiry() {
        StubTransport t = new StubTransport().fail(true).fail(true).fail(true);
        Inquiry inq = connector(t).inquire(biller("KPLC", 3, 5), "12345678");
        assertThat(inq.found()).isFalse();
        assertThat(inq.responseCode()).isEqualTo("91");
    }
}
