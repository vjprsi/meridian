package io.meridian.payment.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.web.client.ServiceClient;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** routing-service: rail selection with cut-off, outbound pacs.008, rail settlement accounts, bank directory. */
@Component
public class RoutingClient {
    static final String ROUTING = "routing-service";

    private final ServiceClient client;
    private final JsonMapper json;

    public RoutingClient(ServiceClient client, JsonMapper json) {
        this.client = client;
        this.json = json;
    }

    /** {@code {routable, payment_rail_id, rail_code, settlement_account_id, execution_date, ...}}. */
    public JsonNode route(ObjectNode payment, String key) {
        ObjectNode body = json.createObjectNode();
        body.set("payment", payment);
        return client.post(ROUTING, "/v1/routing-rules/route", body, LedgerClient.key(key));
    }

    /** Builds, stores and sends the pacs.008 for a payment; returns the rail message. */
    public JsonNode sendPacs008(UUID railId, ObjectNode payment, String key) {
        ObjectNode body = json.createObjectNode();
        body.put("payment_rail_id", railId.toString());
        body.set("payment", payment);
        return client.post(ROUTING, "/v1/rail-messages/pacs008", body, LedgerClient.key(key));
    }

    public Optional<JsonNode> rail(UUID railId) {
        if (railId == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(client.get(ROUTING, "/v1/payment-rails/" + railId));
        } catch (ApiException e) {
            if (e.status() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    public Optional<JsonNode> railByCode(String code) {
        JsonNode page = client.get(ROUTING, "/v1/payment-rails?limit=1&code=" + URLEncoder.encode(code, StandardCharsets.UTF_8));
        JsonNode data = page.path("data");
        return data.isArray() && !data.isEmpty() ? Optional.of(data.get(0)) : Optional.empty();
    }

    /** Bank directory lookup by IBAN and/or BIC: {@code {found, bic, name, reachable_rails, ...}}. */
    public JsonNode lookupParticipant(String iban, String bic, String key) {
        ObjectNode body = json.createObjectNode();
        if (iban != null) {
            body.put("iban", iban);
        }
        if (bic != null) {
            body.put("bic", bic);
        }
        return client.post(ROUTING, "/v1/rail-participants/lookup", body, LedgerClient.key(key));
    }
}
