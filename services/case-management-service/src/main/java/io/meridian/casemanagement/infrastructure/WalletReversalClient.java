package io.meridian.casemanagement.infrastructure;

import io.meridian.casemanagement.application.WalletReversalPort;
import io.meridian.common.error.ApiException;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link WalletReversalPort} over wallet-service's reversal endpoint via {@link ServiceClient} (tenant, user and
 * token propagated, so the current principal is the reversal's checker — a different user from the maker, which
 * wallet-service's four-eyes check also enforces). WE-OPS-1.
 */
@Component
public class WalletReversalClient implements WalletReversalPort {
    static final String WALLET = "wallet-service";

    private final ServiceClient client;
    private final JsonMapper json;

    public WalletReversalClient(ServiceClient client, JsonMapper json) {
        this.client = client;
        this.json = json;
    }

    @Override
    public UUID reverse(UUID walletTransactionId, BigDecimal amount, String currency, String reason,
                        String idempotencyKey) {
        ObjectNode body = json.createObjectNode();
        body.put("reason", reason == null ? "Wallet transaction correction (WE-OPS-1)" : reason);
        if (amount != null) {
            body.put("amount", amount.toPlainString());
        }
        JsonNode reversal = client.post(WALLET, "/v1/wallet-transactions/" + walletTransactionId + "/reverse",
                body, idempotencyKey);
        if (reversal == null || !reversal.hasNonNull("id")) {
            throw new ApiException(502, "upstream_error", "wallet-service returned no reversal transaction id");
        }
        return UUID.fromString(reversal.get("id").asString());
    }
}
