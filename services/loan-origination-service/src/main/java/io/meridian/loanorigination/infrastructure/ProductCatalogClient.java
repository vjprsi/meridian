package io.meridian.loanorigination.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.loanorigination.application.ProductTerms;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Reads lending products and their terms from product-catalog-service. */
@Component
public class ProductCatalogClient {
    static final String CATALOG = "product-catalog-service";
    private static final Set<String> SELLABLE = Set.of("PUBLISHED", "APPROVED");

    private final ServiceClient client;

    public ProductCatalogClient(ServiceClient client) {
        this.client = client;
    }

    /**
     * Terms of a product version; when {@code versionId} is null the product's current version is used. Fails with
     * 422 when the product or version cannot be sold.
     */
    public ProductTerms terms(UUID productId, UUID versionId) {
        JsonNode product;
        try {
            product = client.get(CATALOG, "/v1/products/" + productId);
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw ApiException.unprocessable("product_not_found", "Product " + productId + " does not exist");
            }
            throw e;
        }
        if (product.has("is_sellable") && !product.path("is_sellable").asBoolean(true)) {
            throw ApiException.unprocessable("product_not_sellable", "Product " + productId + " is not open for new sales");
        }
        UUID vid = versionId;
        if (vid == null) {
            String current = text(product, "current_version_id");
            if (current == null) {
                throw ApiException.unprocessable("product_not_sellable", "Product " + productId + " has no published version");
            }
            vid = UUID.fromString(current);
        }
        JsonNode version;
        try {
            version = client.get(CATALOG, "/v1/product-versions/" + vid);
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw ApiException.unprocessable("product_version_not_found", "Product version " + vid + " does not exist");
            }
            throw e;
        }
        if (!productId.toString().equals(version.path("product_id").asString(""))) {
            throw ApiException.unprocessable("product_version_mismatch",
                    "Product version " + vid + " does not belong to product " + productId);
        }
        if (!SELLABLE.contains(version.path("status").asString(""))) {
            throw ApiException.unprocessable("product_version_not_available",
                    "Product version " + vid + " is " + version.path("status").asString("") + "; only published versions can be sold");
        }
        BigDecimal rate = null;
        String dayCount = null;
        for (JsonNode r : client.get(CATALOG, "/v1/product-versions/" + vid + "/interest-rates?limit=200").path("data")) {
            if ("DEBIT_INTEREST".equals(r.path("rate_kind").asString(""))) {
                rate = "ZERO".equals(r.path("rate_type").asString("")) ? BigDecimal.ZERO : dec(r, "fixed_rate");
                dayCount = text(r, "day_count");
                break;
            }
        }
        BigDecimal flat = null;
        BigDecimal pct = null;
        BigDecimal feeMin = null;
        BigDecimal feeMax = null;
        for (JsonNode f : client.get(CATALOG, "/v1/product-versions/" + vid + "/fees?limit=200").path("data")) {
            if ("LOAN_ORIGINATION".equals(f.path("trigger").asString(""))) {
                flat = dec(f, "flat_amount");
                pct = dec(f, "percentage");
                feeMin = dec(f, "min_amount");
                feeMax = dec(f, "max_amount");
                break;
            }
        }
        BigDecimal minAmount = null;
        BigDecimal maxAmount = null;
        Integer minTerm = null;
        Integer maxTerm = null;
        Integer validity = null;
        for (JsonNode p : client.get(CATALOG, "/v1/product-versions/" + vid + "/parameters?limit=200").path("data")) {
            String v = text(p, "value_text");
            if (v == null) {
                continue;
            }
            switch (p.path("param_key").asString("")) {
                case "lending.min_amount" -> minAmount = new BigDecimal(v.trim());
                case "lending.max_amount" -> maxAmount = new BigDecimal(v.trim());
                case "lending.min_term_months" -> minTerm = Integer.parseInt(v.trim());
                case "lending.max_term_months" -> maxTerm = Integer.parseInt(v.trim());
                case "lending.offer_validity_days" -> validity = Integer.parseInt(v.trim());
                default -> { }
            }
        }
        String le = text(product, "legal_entity_id");
        return new ProductTerms(productId, vid, le == null ? null : UUID.fromString(le), text(product, "currency"),
                text(product, "family"), rate, dayCount, flat, pct, feeMin, feeMax, minAmount, maxAmount, minTerm,
                maxTerm, validity);
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static BigDecimal dec(JsonNode n, String f) {
        String s = text(n, f);
        return s == null || s.isBlank() ? null : new BigDecimal(s);
    }
}
