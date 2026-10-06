package io.meridian.loan.application;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/** Typed view of the product terms snapshot pinned on a loan ({@code product_terms}). */
public record ProductTerms(JsonNode snapshot) {

    public static ProductTerms of(JsonNode loanOrSnapshot) {
        JsonNode s = loanOrSnapshot == null ? null
                : loanOrSnapshot.has("product_terms") ? loanOrSnapshot.get("product_terms") : loanOrSnapshot;
        return new ProductTerms(s == null || s.isNull() ? null : s);
    }

    public Optional<BigDecimal> rate(String kind) {
        if (snapshot == null) {
            return Optional.empty();
        }
        for (JsonNode r : snapshot.path("interest_rates")) {
            if (kind.equals(r.path("rate_kind").asString(""))) {
                if ("ZERO".equals(r.path("rate_type").asString(""))) {
                    return Optional.of(BigDecimal.ZERO);
                }
                if (r.hasNonNull("fixed_rate")) {
                    return Optional.of(new BigDecimal(r.get("fixed_rate").asString()));
                }
            }
        }
        return Optional.empty();
    }

    public Optional<String> dayCount(String kind) {
        if (snapshot == null) {
            return Optional.empty();
        }
        for (JsonNode r : snapshot.path("interest_rates")) {
            if (kind.equals(r.path("rate_kind").asString("")) && r.hasNonNull("day_count")) {
                return Optional.of(r.get("day_count").asString());
            }
        }
        return Optional.empty();
    }

    public Optional<JsonNode> fee(String trigger) {
        if (snapshot == null) {
            return Optional.empty();
        }
        for (JsonNode f : snapshot.path("fees")) {
            if (trigger.equals(f.path("trigger").asString(""))) {
                return Optional.of(f);
            }
        }
        return Optional.empty();
    }

    /** Fee amount for a base amount: FLAT, PERCENTAGE or FLAT_PLUS_PERCENTAGE with min/max caps, rounded to scale. */
    public Optional<BigDecimal> feeAmount(String trigger, BigDecimal base, int scale) {
        return fee(trigger).map(f -> {
            String method = f.path("calculation_method").asString("FLAT");
            BigDecimal flat = dec(f, "flat_amount");
            BigDecimal pct = dec(f, "percentage");
            BigDecimal amt = switch (method) {
                case "PERCENTAGE", "TIERED_PERCENTAGE" -> base.multiply(pct, MathContext.DECIMAL128);
                case "FLAT_PLUS_PERCENTAGE" -> flat.add(base.multiply(pct, MathContext.DECIMAL128));
                default -> flat;
            };
            if (f.hasNonNull("min_amount")) {
                amt = amt.max(dec(f, "min_amount"));
            }
            if (f.hasNonNull("max_amount")) {
                amt = amt.min(dec(f, "max_amount"));
            }
            return amt.setScale(scale, RoundingMode.HALF_EVEN);
        });
    }

    public Optional<BigDecimal> decimalParam(String key) {
        String v = param(key);
        return v == null || v.isBlank() ? Optional.empty() : Optional.of(new BigDecimal(v.trim()));
    }

    public Optional<Integer> intParam(String key) {
        String v = param(key);
        return v == null || v.isBlank() ? Optional.empty() : Optional.of(Integer.parseInt(v.trim()));
    }

    private String param(String key) {
        if (snapshot == null) {
            return null;
        }
        JsonNode v = snapshot.path("parameters").get(key);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static BigDecimal dec(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? BigDecimal.ZERO : new BigDecimal(v.asString());
    }
}
