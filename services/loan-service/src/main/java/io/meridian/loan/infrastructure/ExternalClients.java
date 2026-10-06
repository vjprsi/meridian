package io.meridian.loan.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.web.client.ServiceClient;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Reads from deposit-service (customer accounts) and product-catalog-service (lending product terms). */
@Component
public class ExternalClients {
    static final String DEPOSITS = "deposit-service";
    static final String CATALOG = "product-catalog-service";
    private static final Set<String> SELLABLE = Set.of("PUBLISHED", "APPROVED");
    private static final Set<String> POSTABLE = Set.of("ACTIVE", "DORMANT", "INACTIVE");

    private final ServiceClient client;
    private final JsonMapper json;

    public ExternalClients(ServiceClient client, JsonMapper json) {
        this.client = client;
        this.json = json;
    }

    /**
     * A customer deposit account that can receive or pay funds; 422 when it does not exist, has no ledger account,
     * is in another currency or is not open.
     */
    public UUID depositLedgerAccount(UUID depositAccountId, String currency, String field) {
        JsonNode acc;
        try {
            acc = client.get(DEPOSITS, "/v1/deposit-accounts/" + depositAccountId);
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw ApiException.unprocessable("deposit_account_not_found",
                        field + " " + depositAccountId + " does not reference an existing deposit account");
            }
            throw e;
        }
        String status = acc.path("status").asString("");
        if (!POSTABLE.contains(status)) {
            throw ApiException.unprocessable("deposit_account_not_active",
                    "Deposit account " + depositAccountId + " is " + status);
        }
        if (currency != null && acc.hasNonNull("currency") && !currency.equals(acc.get("currency").asString())) {
            throw ApiException.unprocessable("currency_mismatch",
                    "Deposit account " + depositAccountId + " is in " + acc.get("currency").asString() + ", not " + currency);
        }
        String ledger = acc.path("ledger_account_id").asString(null);
        if (ledger == null) {
            throw ApiException.unprocessable("deposit_account_not_open",
                    "Deposit account " + depositAccountId + " has no ledger account yet");
        }
        return UUID.fromString(ledger);
    }

    /**
     * Product terms snapshot pinned on a loan or credit line: {@code product_id, product_version_id, interest_rates,
     * fees, parameters{key: value}}. Rejects versions that are not published/approved or belong to another product.
     */
    public ObjectNode productTerms(UUID productId, UUID versionId, String currency) {
        JsonNode version;
        try {
            version = client.get(CATALOG, "/v1/product-versions/" + versionId);
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw ApiException.unprocessable("product_version_not_found", "Product version " + versionId + " not found");
            }
            throw e;
        }
        if (!productId.toString().equals(version.path("product_id").asString(""))) {
            throw ApiException.unprocessable("product_version_mismatch",
                    "Product version " + versionId + " does not belong to product " + productId);
        }
        String status = version.path("status").asString("");
        if (!SELLABLE.contains(status)) {
            throw ApiException.unprocessable("product_version_not_available",
                    "Product version " + versionId + " is " + status + "; only published versions can be used");
        }
        ObjectNode snap = json.createObjectNode();
        snap.put("product_id", productId.toString());
        snap.put("product_version_id", versionId.toString());
        ArrayNode rates = snap.putArray("interest_rates");
        for (JsonNode r : list("/v1/product-versions/" + versionId + "/interest-rates?limit=200")) {
            if (!r.hasNonNull("currency") || currency == null || currency.equals(r.get("currency").asString())) {
                rates.add(r);
            }
        }
        ArrayNode fees = snap.putArray("fees");
        for (JsonNode f : list("/v1/product-versions/" + versionId + "/fees?limit=200")) {
            if (!f.hasNonNull("currency") || currency == null || currency.equals(f.get("currency").asString())) {
                fees.add(f);
            }
        }
        ObjectNode params = snap.putObject("parameters");
        for (JsonNode p : list("/v1/product-versions/" + versionId + "/parameters?limit=200")) {
            if (p.hasNonNull("param_key")) {
                params.put(p.get("param_key").asString(), p.path("value_text").asString(null));
            }
        }
        return snap;
    }

    private JsonNode list(String path) {
        try {
            return client.get(CATALOG, path).path("data");
        } catch (ApiException e) {
            if (e.status() == 404) {
                return json.createArrayNode();
            }
            throw e;
        }
    }
}
