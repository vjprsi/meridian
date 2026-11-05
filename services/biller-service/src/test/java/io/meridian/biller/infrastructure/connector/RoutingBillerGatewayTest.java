package io.meridian.biller.infrastructure.connector;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.biller.application.BillerGateway;
import io.meridian.biller.infrastructure.SimulatedBillerGateway;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class RoutingBillerGatewayTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final SimulatedBillerGateway simulator = new SimulatedBillerGateway();

    /** A connector that claims only billers whose aggregator_name matches, and tags its results so we can see it ran. */
    private static final class FakeConnector implements BillerConnector {
        private final String aggregator;
        FakeConnector(String aggregator) {
            this.aggregator = aggregator;
        }
        @Override public boolean supports(JsonNode biller) {
            return aggregator.equals(biller.path("aggregator_name").asString(""));
        }
        @Override public Inquiry inquire(JsonNode b, String ref) {
            return new Inquiry("00", "FAKE", "Fake", null, null, "FAKE");
        }
        @Override public Result pay(JsonNode b, JsonNode p, int attempt) {
            return new Result(Outcome.SUCCESS, "FAKE-REF", null, null, "fake");
        }
        @Override public Result topup(JsonNode b, JsonNode t, int attempt) {
            return new Result(Outcome.SUCCESS, "FAKE-REF", null, null, "fake");
        }
    }

    private ObjectNode biller(String code, String aggregator) {
        ObjectNode b = json.createObjectNode().put("biller_code", code).put("category", "ELECTRICITY");
        if (aggregator != null) {
            b.put("integration_type", "AGGREGATOR").put("aggregator_name", aggregator);
        } else {
            b.put("integration_type", "API");
        }
        return b;
    }

    @Test
    void fallsBackToTheSimulatorWhenNoConnectorClaimsTheBiller() {
        RoutingBillerGateway routing = new RoutingBillerGateway(List.of(new FakeConnector("WIRECARD")), simulator);
        assertThat(routing.select(biller("KPLC", null))).isSameAs(simulator);
        // and the delegated call is the simulator's deterministic behaviour (provider ref prefixed with the code)
        ObjectNode payment = json.createObjectNode().put("id", "p1").put("account_reference", "12345678");
        assertThat(routing.pay(biller("KPLC", null), payment, 1).providerReference()).startsWith("KPLC-");
    }

    @Test
    void selectsTheConnectorThatClaimsTheBiller() {
        FakeConnector fake = new FakeConnector("WIRECARD");
        RoutingBillerGateway routing = new RoutingBillerGateway(List.of(fake), simulator);
        assertThat(routing.select(biller("ACME", "WIRECARD"))).isSameAs(fake);
        assertThat(routing.pay(biller("ACME", "WIRECARD"), json.createObjectNode(), 1).providerReference())
                .isEqualTo("FAKE-REF");
    }

    @Test
    void picksTheFirstMatchingConnectorInOrder() {
        FakeConnector first = new FakeConnector("CLARO_CO");
        FakeConnector second = new FakeConnector("CLARO_CO");
        RoutingBillerGateway routing = new RoutingBillerGateway(List.of(first, second), simulator);
        assertThat(routing.select(biller("CLR", "CLARO_CO"))).isSameAs(first);
    }

    @Test
    void aConnectorThrowingDuringSupportIsSkipped() {
        BillerConnector broken = new BillerConnector() {
            @Override public boolean supports(JsonNode biller) {
                throw new IllegalStateException("boom");
            }
            @Override public BillerGateway.Inquiry inquire(JsonNode b, String ref) {
                throw new UnsupportedOperationException();
            }
            @Override public BillerGateway.Result pay(JsonNode b, JsonNode p, int a) {
                throw new UnsupportedOperationException();
            }
            @Override public BillerGateway.Result topup(JsonNode b, JsonNode t, int a) {
                throw new UnsupportedOperationException();
            }
        };
        RoutingBillerGateway routing = new RoutingBillerGateway(List.of(broken), simulator);
        assertThat(routing.select(biller("KPLC", null))).isSameAs(simulator);
    }
}
