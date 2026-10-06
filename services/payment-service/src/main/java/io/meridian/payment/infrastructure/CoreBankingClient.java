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

/** deposit-service accounts, customer-service names and notification-service delivery used by payments. */
@Component
public class CoreBankingClient {
    private final ServiceClient client;
    private final JsonMapper json;

    public CoreBankingClient(ServiceClient client, JsonMapper json) {
        this.client = client;
        this.json = json;
    }

    /** A deposit account; 422 {@code <role>_account_not_found} when it does not exist. */
    public JsonNode depositAccount(UUID id, String role) {
        try {
            return client.get("deposit-service", "/v1/deposit-accounts/" + id);
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw ApiException.unprocessable(role + "_account_not_found", "Deposit account " + id + " does not exist");
            }
            throw e;
        }
    }

    /** On-us account by IBAN or account number. */
    public Optional<JsonNode> findDepositAccount(String idType, String number) {
        String filter = "IBAN".equals(idType) ? "iban" : "account_number";
        JsonNode page = client.get("deposit-service", "/v1/deposit-accounts?limit=1&" + filter + "="
                + URLEncoder.encode(number, StandardCharsets.UTF_8));
        JsonNode data = page.path("data");
        return data.isArray() && !data.isEmpty() ? Optional.of(data.get(0)) : Optional.empty();
    }

    public Optional<JsonNode> customer(UUID id) {
        try {
            return Optional.of(client.get("customer-service", "/v1/customers/" + id));
        } catch (ApiException e) {
            if (e.status() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /** Legal / display name of a customer for name checks. */
    public Optional<String> customerName(UUID id) {
        return customer(id).map(c -> {
            String first = c.path("first_name").asString(null);
            String last = c.path("last_name").asString(null);
            if (first != null && last != null) {
                return first + " " + last;
            }
            String legal = c.path("legal_name").asString(null);
            return legal != null ? legal : c.path("display_name").asString(null);
        });
    }

    /** Sends a templated notification (e.g. an OTP) to a customer. */
    public void notifyCustomer(UUID customerId, String template, String channel, ObjectNode variables, String key) {
        ObjectNode body = json.createObjectNode();
        body.put("template_code", template);
        body.put("recipient_type", "CUSTOMER");
        body.put("recipient_id", customerId.toString());
        body.put("channel", channel);
        body.set("variables", variables);
        client.post("notification-service", "/v1/notifications/send-templated", body, LedgerClient.key(key));
    }
}
