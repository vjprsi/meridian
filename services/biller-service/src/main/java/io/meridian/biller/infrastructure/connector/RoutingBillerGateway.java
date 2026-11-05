package io.meridian.biller.infrastructure.connector;

import io.meridian.biller.application.BillerGateway;
import io.meridian.biller.infrastructure.SimulatedBillerGateway;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The biller port the application layer ({@code PaymentService}, {@code CatalogService}) injects. It is a thin
 * selector: for every call it resolves the connector that matches the biller's routing metadata
 * ({@code integration_type}, {@code aggregator_name}, {@code connection_config}) and delegates to it, falling back to
 * {@link SimulatedBillerGateway} when no real connector is configured — i.e. when no {@link BillerConnector} claims
 * the biller, which in practice means the biller has no usable {@code connection_config.base_url}.
 *
 * <p>Marked {@link Primary @Primary} so it is chosen over the plain simulator bean. Existing billers and all tests
 * carry no connection config, so they keep the simulator's behaviour unchanged. Connectors are injected in
 * {@link org.springframework.core.annotation.Order @Order} order (specific before generic).
 */
@Component
@Primary
public class RoutingBillerGateway implements BillerGateway {
    private static final Logger log = LoggerFactory.getLogger(RoutingBillerGateway.class);

    private final List<BillerConnector> connectors;
    private final SimulatedBillerGateway simulator;

    public RoutingBillerGateway(List<BillerConnector> connectors, SimulatedBillerGateway simulator) {
        this.connectors = connectors;
        this.simulator = simulator;
    }

    /** The connector that handles this biller, or the simulator when none is configured. */
    BillerGateway select(JsonNode biller) {
        for (BillerConnector c : connectors) {
            try {
                if (c.supports(biller)) {
                    return c;
                }
            } catch (RuntimeException e) {
                log.warn("biller connector {} failed to evaluate support; skipping",
                        c.getClass().getSimpleName(), e);
            }
        }
        return simulator;
    }

    @Override
    public Inquiry inquire(JsonNode biller, String accountReference) {
        return select(biller).inquire(biller, accountReference);
    }

    @Override
    public Result pay(JsonNode biller, JsonNode payment, int attempt) {
        return select(biller).pay(biller, payment, attempt);
    }

    @Override
    public Result topup(JsonNode biller, JsonNode topup, int attempt) {
        return select(biller).topup(biller, topup, attempt);
    }
}
