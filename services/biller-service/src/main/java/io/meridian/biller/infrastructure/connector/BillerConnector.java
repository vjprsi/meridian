package io.meridian.biller.infrastructure.connector;

import io.meridian.biller.application.BillerGateway;
import tools.jackson.databind.JsonNode;

/**
 * A real biller / mobile-operator connector. {@link RoutingBillerGateway} selects one from a biller's routing
 * metadata ({@code integration_type}, {@code aggregator_name}, {@code connection_config}): the first connector whose
 * {@link #supports(JsonNode)} returns true handles the call, otherwise the built-in simulator does. Connectors are
 * ordered with {@link org.springframework.core.annotation.Order @Order} (lower first), so a specific connector
 * (e.g. Claro) is tried before the generic REST connector.
 */
public interface BillerConnector extends BillerGateway {

    /** True if this connector should handle calls for the given biller row. Never throws. */
    boolean supports(JsonNode biller);
}
