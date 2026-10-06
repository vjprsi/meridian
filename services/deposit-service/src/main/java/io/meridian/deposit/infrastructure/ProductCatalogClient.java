package io.meridian.deposit.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.web.client.ServiceClient;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Reads a product version's interest and fee terms from product-catalog-service to pin them on an account. */
@Component
public class ProductCatalogClient {
    static final String CATALOG = "product-catalog-service";
    private static final Set<String> SELLABLE = Set.of("PUBLISHED", "APPROVED");

    private final ServiceClient client;
    private final JsonMapper json;

    public ProductCatalogClient(ServiceClient client, JsonMapper json) {
        this.client = client;
        this.json = json;
    }

    /** Builds a product snapshot ({@code interest_rates} with tiers, {@code fees}) for one version and currency. */
    public ObjectNode snapshot(UUID productId, UUID versionId, String currency) {
        JsonNode version = client.get(CATALOG, "/v1/product-versions/" + versionId);
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
        for (JsonNode r : client.get(CATALOG, "/v1/product-versions/" + versionId + "/interest-rates?limit=200").path("data")) {
            if (r.hasNonNull("currency") && !currency.equals(r.get("currency").asString())) {
                continue;
            }
            ObjectNode copy = ((ObjectNode) r).deepCopy();
            String type = r.path("rate_type").asString("FIXED");
            if (("TIERED".equals(type) || "BANDED".equals(type)) && r.hasNonNull("id")) {
                copy.set("tiers", client.get(CATALOG, "/v1/interest-rate-configs/" + r.get("id").asString()
                        + "/tiers?limit=200").path("data"));
            }
            rates.add(copy);
        }
        ArrayNode fees = snap.putArray("fees");
        for (JsonNode f : client.get(CATALOG, "/v1/product-versions/" + versionId + "/fees?limit=200").path("data")) {
            if (!f.hasNonNull("currency") || currency.equals(f.get("currency").asString())) {
                fees.add(f);
            }
        }
        return snap;
    }
}
