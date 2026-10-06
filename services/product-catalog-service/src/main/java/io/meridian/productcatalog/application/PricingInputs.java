package io.meridian.productcatalog.application;

import io.meridian.common.error.ApiException;
import io.meridian.productcatalog.domain.fees.FeeCalculator;
import io.meridian.productcatalog.domain.rates.DayCount;
import io.meridian.productcatalog.domain.rates.RateCalculator;
import io.meridian.productcatalog.infrastructure.CatalogStore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Loads stored configuration rows and maps them to the pure calculators' inputs. */
@Component
public class PricingInputs {
    private final CatalogStore store;

    public PricingInputs(CatalogStore store) {
        this.store = store;
    }

    public RateCalculator.Config rateConfig(JsonNode cfg) {
        List<RateCalculator.Tier> tiers = store.children("interest_rate_tier", "interest_rate_config_id",
                UUID.fromString(cfg.get("id").asString()), "tier_no").stream()
                .map(t -> new RateCalculator.Tier(t.get("tier_no").asInt(), dec(t, "from_amount", BigDecimal.ZERO),
                        dec(t, "to_amount", null), integer(t, "from_day"), integer(t, "to_day"), dec(t, "rate", null),
                        dec(t, "spread", null)))
                .toList();
        return new RateCalculator.Config(cfg.get("rate_type").asString(), dec(cfg, "fixed_rate", null),
                dec(cfg, "spread", null), dec(cfg, "floor_rate", null), dec(cfg, "cap_rate", null),
                dec(cfg, "min_balance_for_interest", BigDecimal.ZERO), DayCount.of(text(cfg, "day_count")),
                RateCalculator.rounding(text(cfg, "rounding")), cfg.path("rounding_scale").asInt(2),
                date(cfg, "effective_from"), date(cfg, "effective_to"), tiers);
    }

    /** Index fixing applicable on {@code date}: latest value on or before date minus the publication lag. */
    public Optional<BigDecimal> indexRate(JsonNode cfg, LocalDate date) {
        if (!cfg.hasNonNull("rate_index_id")) {
            return Optional.empty();
        }
        UUID indexId = UUID.fromString(cfg.get("rate_index_id").asString());
        JsonNode index = store.require("rate_index", indexId);
        int lag = index.path("publication_lag_days").asInt(0);
        return store.indexRateAt(indexId, date.minusDays(lag));
    }

    public FeeCalculator.Fee fee(JsonNode f) {
        UUID id = UUID.fromString(f.get("id").asString());
        List<FeeCalculator.Tier> tiers = store.children("fee_tier", "fee_definition_id", id, "tier_no").stream()
                .map(t -> new FeeCalculator.Tier(t.get("tier_no").asInt(), dec(t, "from_value", BigDecimal.ZERO),
                        dec(t, "to_value", null), dec(t, "flat_amount", null), dec(t, "percentage", null)))
                .toList();
        List<FeeCalculator.Waiver> waivers = store.children("fee_waiver_rule", "fee_definition_id", id, "id").stream()
                .map(w -> new FeeCalculator.Waiver(UUID.fromString(w.get("id").asString()), w.get("condition").asString(),
                        dec(w, "threshold_amount", null), integer(w, "threshold_count"), text(w, "segment"),
                        dec(w, "discount_percentage", BigDecimal.ONE), text(w, "expression"), date(w, "valid_from"),
                        date(w, "valid_to")))
                .toList();
        return new FeeCalculator.Fee(id, f.get("code").asString(), f.get("calculation_method").asString(),
                dec(f, "flat_amount", null), dec(f, "percentage", null), dec(f, "min_amount", null),
                dec(f, "max_amount", null), dec(f, "cap_per_period", null), f.path("free_count").asInt(0),
                f.get("currency").asString().trim(), date(f, "effective_from"), date(f, "effective_to"), tiers, waivers);
    }

    /** Tax rule of a fee when it is valid on {@code asOf}; empty when none is linked. */
    public Optional<FeeCalculator.Tax> tax(JsonNode fee, LocalDate asOf) {
        if (!fee.hasNonNull("tax_rule_id")) {
            return Optional.empty();
        }
        JsonNode t = store.require("tax_rule", UUID.fromString(fee.get("tax_rule_id").asString()));
        LocalDate from = date(t, "valid_from");
        LocalDate to = date(t, "valid_to");
        if (from != null && asOf.isBefore(from) || to != null && asOf.isAfter(to)) {
            throw ApiException.unprocessable("tax_rule_not_effective", "Tax rule " + t.get("code").asString()
                    + " is not valid on " + asOf);
        }
        return Optional.of(new FeeCalculator.Tax(UUID.fromString(t.get("id").asString()), t.get("code").asString(),
                dec(t, "rate", BigDecimal.ZERO), null));
    }

    static BigDecimal dec(JsonNode n, String f, BigDecimal def) {
        if (n == null || !n.hasNonNull(f)) {
            return def;
        }
        JsonNode v = n.get(f);
        return v.isNumber() ? v.decimalValue() : new BigDecimal(v.asString().trim());
    }

    static Integer integer(JsonNode n, String f) {
        return n.hasNonNull(f) ? n.get(f).asInt() : null;
    }

    static String text(JsonNode n, String f) {
        return n != null && n.hasNonNull(f) ? n.get(f).asString() : null;
    }

    static LocalDate date(JsonNode n, String f) {
        return n.hasNonNull(f) ? LocalDate.parse(n.get(f).asString().substring(0, 10)) : null;
    }
}
