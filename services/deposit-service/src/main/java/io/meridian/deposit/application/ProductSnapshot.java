package io.meridian.deposit.application;

import io.meridian.common.error.ApiException;
import io.meridian.deposit.domain.DayCount;
import io.meridian.deposit.domain.FeeCalculator;
import io.meridian.deposit.domain.InterestCalculator;
import io.meridian.deposit.domain.InterestCalculator.RateConfig;
import io.meridian.deposit.domain.Schedule;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * The product terms pinned on an account or term deposit ({@code product_terms} column, copied from the product
 * catalog at opening, placement or product change), in the product catalog's shapes:
 * <pre>
 * { "interest_rates": [ { "rate_kind": "CREDIT_INTEREST", "rate_type": "TIERED", "fixed_rate": "0.02",
 *                         "day_count": "ACT_365", "min_balance_for_interest": "0", "capitalisation_frequency": "MONTHLY",
 *                         "floor_rate": null, "cap_rate": null,
 *                         "tiers": [ { "from_amount": "0", "to_amount": "10000", "rate": "0.01" }, ... ] } ],
 *   "fees": [ { "code": "MONTHLY_MAINTENANCE", "calculation_method": "FLAT", "flat_amount": "5.00", "percentage": null,
 *               "min_amount": null, "max_amount": null, "tax_rate": "0.20", "is_refundable": true, "trigger": "..." } ],
 *   "withholding_tax_rate": "0.20", "overdraft": { "max_limit": "5000" }, "allow_top_up": true }
 * </pre>
 */
public final class ProductSnapshot {
    private final JsonNode root;

    private ProductSnapshot(JsonNode root) {
        this.root = root;
    }

    /** The terms pinned on a deposit_account or term_deposit row. */
    public static ProductSnapshot of(JsonNode row) {
        return terms(row.path("product_terms"));
    }

    /** Terms in the catalog shape (e.g. a snapshot just read from product-catalog-service). */
    public static ProductSnapshot terms(JsonNode terms) {
        return new ProductSnapshot(terms != null && terms.isObject() ? terms : null);
    }

    public boolean present() {
        return root != null;
    }

    /** Rate configuration for an InterestRateKind (CREDIT_INTEREST, OVERDRAFT_INTEREST, ...). */
    public Optional<RateConfig> rate(String rateKind) {
        if (root == null) {
            return Optional.empty();
        }
        for (JsonNode r : root.path("interest_rates")) {
            if (rateKind.equalsIgnoreCase(r.path("rate_kind").asString(""))) {
                return Optional.of(parseRate(r));
            }
        }
        return Optional.empty();
    }

    public Schedule.Frequency capitalisationFrequency() {
        String f = rate("CREDIT_INTEREST").isPresent() ? rawRate("CREDIT_INTEREST").path("capitalisation_frequency")
                .asString("MONTHLY") : "MONTHLY";
        return switch (f.toUpperCase(Locale.ROOT)) {
            case "DAILY" -> Schedule.Frequency.DAILY;
            case "WEEKLY" -> Schedule.Frequency.WEEKLY;
            case "QUARTERLY" -> Schedule.Frequency.QUARTERLY;
            case "SEMI_ANNUAL" -> Schedule.Frequency.SEMI_ANNUAL;
            case "ANNUAL" -> Schedule.Frequency.ANNUAL;
            default -> Schedule.Frequency.MONTHLY;
        };
    }

    public Optional<FeeCalculator.FeeDefinition> fee(String code) {
        if (root == null) {
            return Optional.empty();
        }
        for (JsonNode f : root.path("fees")) {
            if (code.equalsIgnoreCase(f.path("code").asString("")) || code.equalsIgnoreCase(f.path("trigger").asString(""))) {
                return Optional.of(new FeeCalculator.FeeDefinition(f.path("code").asString(code),
                        text(f, "calculation_method"), dec(f, "flat_amount"), dec(f, "percentage"), dec(f, "min_amount"),
                        dec(f, "max_amount"), dec(f, "tax_rate"), !f.has("is_refundable") || f.path("is_refundable").asBoolean()));
            }
        }
        return Optional.empty();
    }

    public Optional<BigDecimal> withholdingTaxRate() {
        return root == null ? Optional.empty() : Optional.ofNullable(dec(root, "withholding_tax_rate"));
    }

    public Optional<BigDecimal> overdraftMaxLimit() {
        return root == null ? Optional.empty() : Optional.ofNullable(dec(root.path("overdraft"), "max_limit"));
    }

    public boolean flag(String name) {
        return root != null && root.path(name).asBoolean(false);
    }

    private JsonNode rawRate(String kind) {
        for (JsonNode r : root.path("interest_rates")) {
            if (kind.equalsIgnoreCase(r.path("rate_kind").asString(""))) {
                return r;
            }
        }
        return root;
    }

    static RateConfig parseRate(JsonNode r) {
        try {
            List<InterestCalculator.Tier> tiers = new ArrayList<>();
            for (JsonNode t : r.path("tiers")) {
                tiers.add(new InterestCalculator.Tier(dec(t, "from_amount"), dec(t, "to_amount"), dec(t, "rate")));
            }
            InterestCalculator.Structure structure = InterestCalculator.RateConfig.structureOf(text(r, "rate_type"));
            if (structure == InterestCalculator.Structure.FIXED && dec(r, "fixed_rate") == null && !tiers.isEmpty()) {
                structure = InterestCalculator.Structure.TIERED;
            }
            return new RateConfig(r.path("rate_kind").asString(""), structure, dec(r, "fixed_rate"), tiers,
                    DayCount.parse(text(r, "day_count")), dec(r, "min_balance_for_interest"), dec(r, "floor_rate"),
                    dec(r, "cap_rate"));
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("invalid_product_terms", "Product rate configuration is invalid: "
                    + e.getMessage());
        }
    }

    static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asString();
    }

    static BigDecimal dec(JsonNode n, String f) {
        JsonNode v = n.get(f);
        if (v == null || v.isNull() || (v.isString() && v.asString().isBlank())) {
            return null;
        }
        return v.isNumber() ? v.decimalValue() : new BigDecimal(v.asString());
    }
}
