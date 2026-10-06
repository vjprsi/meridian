package io.meridian.disbursement.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.disbursement.application.WalletPort;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** {@link WalletPort} over wallet-service's HTTP API via {@link ServiceClient} (tenant, user and token propagated). */
@Component
public class WalletClient implements WalletPort {
    static final String WALLET = "wallet-service";

    private final ServiceClient client;
    private final JsonMapper json;

    public WalletClient(ServiceClient client, JsonMapper json) {
        this.client = client;
        this.json = json;
    }

    @Override
    public JsonNode wallet(UUID walletId) {
        return client.get(WALLET, "/v1/wallets/" + walletId);
    }

    @Override
    public Optional<JsonNode> lookup(String msisdn, String currency) {
        try {
            return Optional.of(client.get(WALLET, "/v1/wallets/lookup?msisdn=" + enc(msisdn) + "&currency=" + enc(currency)));
        } catch (ApiException e) {
            if (e.status() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public JsonNode openTierZeroWallet(String key, String msisdn, String displayName, String currency) {
        ObjectNode body = json.createObjectNode();
        body.put("wallet_type", "CUSTOMER");
        body.put("display_name", displayName.length() > 160 ? displayName.substring(0, 160) : displayName);
        body.put("currency", currency);
        body.put("msisdn", msisdn);
        body.put("kyc_tier", 0);
        body.put("opened_channel", "BULK");
        JsonNode created = client.post(WALLET, "/v1/wallets", body, key);
        String id = created.get("id").asString();
        ObjectNode activate = json.createObjectNode();
        activate.put("reason", "Opened for a bulk disbursement beneficiary");
        return client.post(WALLET, "/v1/wallets/" + id + "/activate", activate, key + "-activate");
    }

    @Override
    public BigDecimal available(UUID walletId) {
        JsonNode b = client.get(WALLET, "/v1/wallets/" + walletId + "/balance");
        String v = b.path("available_balance").asString(null);
        if (v == null) {
            throw new ApiException(502, "upstream_error", "wallet-service returned no available_balance");
        }
        return new BigDecimal(v);
    }

    @Override
    public JsonNode payout(String key, Payout p) {
        ObjectNode body = json.createObjectNode();
        body.put("txn_type", p.txnType());
        body.put("channel", "BULK");
        body.put("initiator_type", "BULK");
        body.put("payer_wallet_id", p.payerWalletId().toString());
        body.put("payee_wallet_id", p.payeeWalletId().toString());
        body.put("amount", p.amount().toPlainString());
        body.put("currency", p.currency());
        body.put("disbursement_beneficiary_id", p.beneficiaryId().toString());
        if (p.description() != null) {
            body.put("description", p.description().length() > 255 ? p.description().substring(0, 255) : p.description());
        }
        return client.post(WALLET, "/v1/wallet-transactions", body, key);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
