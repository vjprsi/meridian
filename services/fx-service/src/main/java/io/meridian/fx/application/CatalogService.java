package io.meridian.fx.application;

import static io.meridian.fx.infrastructure.Rows.dec;
import static io.meridian.fx.infrastructure.Rows.str;
import static io.meridian.fx.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.fx.domain.FxPricing;
import io.meridian.fx.domain.MarginRules;
import io.meridian.fx.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Validation of margin rules, remittance partners and corridors, and corridor suspend / resume. */
@Service
public class CatalogService {
    static final String CORRIDOR = "remittance_corridor";
    static final Set<String> PAYOUT_METHODS = Set.of("BANK_ACCOUNT", "MOBILE_WALLET", "CASH_PICKUP", "CARD", "HOME_DELIVERY");
    static final Set<String> PARTNER_TYPES = Set.of("BANK", "MTO", "WALLET_OPERATOR", "AGGREGATOR");

    private final Rows rows;
    private final Events events;

    public CatalogService(Rows rows, Events events) {
        this.rows = rows;
        this.events = events;
    }

    // ------------------------------------------------------------------ margin rules

    public void validateMarginRule(JsonNode r, boolean create) {
        if ((create || r.hasNonNull("currency_pair")) && !MarginRules.validPair(str(r, "currency_pair"))) {
            throw ApiException.unprocessable("invalid_currency_pair", "currency_pair must be CCY/CCY with * wildcards, e.g. USD/EUR");
        }
        if (r.hasNonNull("margin_bps") && (r.get("margin_bps").asInt() < 0 || r.get("margin_bps").asInt() > 5000)) {
            throw ApiException.unprocessable("invalid_margin", "margin_bps must be between 0 and 5000");
        }
        BigDecimal min = dec(r, "min_amount");
        BigDecimal max = dec(r, "max_amount");
        if (min != null && min.signum() < 0 || max != null && max.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount_band", "Amount band bounds must be positive");
        }
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw ApiException.unprocessable("invalid_amount_band", "min_amount must not exceed max_amount");
        }
        if (dec(r, "min_fee") != null && dec(r, "min_fee").signum() < 0) {
            throw ApiException.unprocessable("invalid_fee", "min_fee must not be negative");
        }
        LocalDate from = Rows.date(r, "valid_from");
        LocalDate to = Rows.date(r, "valid_to");
        if (from != null && to != null && to.isBefore(from)) {
            throw ApiException.unprocessable("invalid_validity", "valid_to must not be before valid_from");
        }
    }

    // ------------------------------------------------------------------ partners

    public void validatePartner(JsonNode r, boolean create) {
        if (create || r.hasNonNull("countries")) {
            ArrayNode countries = list(r, "countries");
            for (JsonNode c : countries) {
                if (!FxPricing.isCountry(c.asString())) {
                    throw ApiException.unprocessable("invalid_country", "countries must hold ISO 3166 alpha-2 codes: " + c);
                }
            }
        }
        if (create || r.hasNonNull("payout_methods")) {
            for (JsonNode m : list(r, "payout_methods")) {
                if (!PAYOUT_METHODS.contains(m.asString())) {
                    throw ApiException.unprocessable("invalid_payout_method", "Unknown payout method " + m
                            + "; allowed " + PAYOUT_METHODS);
                }
            }
        }
        if (r.hasNonNull("partner_type") && !PARTNER_TYPES.contains(str(r, "partner_type"))) {
            throw ApiException.unprocessable("invalid_partner_type", "partner_type must be one of " + PARTNER_TYPES);
        }
        if (r.hasNonNull("sla_minutes") && r.get("sla_minutes").asInt() <= 0) {
            throw ApiException.unprocessable("invalid_sla", "sla_minutes must be positive");
        }
    }

    private static ArrayNode list(JsonNode r, String f) {
        JsonNode v = r.get(f);
        if (!(v instanceof ArrayNode a) || a.isEmpty()) {
            throw ApiException.unprocessable(f + "_required", f + " must be a non-empty list");
        }
        return a;
    }

    // ------------------------------------------------------------------ corridors

    public void validateCorridor(JsonNode r, UUID existingId) {
        JsonNode merged = r;
        if (existingId != null) {
            ObjectNode cur = rows.get(CORRIDOR, existingId);
            r.propertyNames().forEach(k -> {
                if (!r.get(k).isNull()) {
                    cur.set(k, r.get(k));
                }
            });
            merged = cur;
        }
        for (String f : new String[] {"source_currency", "destination_currency"}) {
            if (!FxPricing.isCurrency(str(merged, f))) {
                throw ApiException.unprocessable("invalid_currency", f + " must be an ISO 4217 currency code");
            }
        }
        for (String f : new String[] {"source_country", "destination_country"}) {
            if (!FxPricing.isCountry(str(merged, f))) {
                throw ApiException.unprocessable("invalid_country", f + " must be an ISO 3166 alpha-2 code");
            }
        }
        BigDecimal min = dec(merged, "min_amount") == null ? BigDecimal.ZERO : dec(merged, "min_amount");
        BigDecimal max = dec(merged, "max_amount");
        if (min.signum() < 0 || max == null || max.signum() <= 0 || min.compareTo(max) > 0) {
            throw ApiException.unprocessable("invalid_amount_range", "Require 0 <= min_amount <= max_amount and max_amount > 0");
        }
        if (dec(merged, "fixed_fee") != null && dec(merged, "fixed_fee").signum() < 0) {
            throw ApiException.unprocessable("invalid_fee", "fixed_fee must not be negative");
        }
        BigDecimal pct = dec(merged, "percent_fee");
        if (pct != null && (pct.signum() < 0 || pct.compareTo(BigDecimal.ONE) >= 0)) {
            throw ApiException.unprocessable("invalid_fee", "percent_fee must be in [0, 1)");
        }
        if (merged.hasNonNull("margin_bps") && (merged.get("margin_bps").asInt() < 0 || merged.get("margin_bps").asInt() > 5000)) {
            throw ApiException.unprocessable("invalid_margin", "margin_bps must be between 0 and 5000");
        }
        UUID partner = uuid(merged, "remittance_partner_id");
        if (partner == null || rows.find("remittance_partner", partner).isEmpty()) {
            throw ApiException.unprocessable("partner_not_found", "remittance_partner_id does not reference a partner");
        }
        UUID fallback = uuid(merged, "fallback_partner_id");
        if (fallback != null && (fallback.equals(partner) || rows.find("remittance_partner", fallback).isEmpty())) {
            throw ApiException.unprocessable("invalid_fallback_partner", "fallback_partner_id must be another existing partner");
        }
    }

    @Transactional
    public ObjectNode setCorridorStatus(UUID id, String target, String reason) {
        ObjectNode c = rows.lock(CORRIDOR, id);
        String from = str(c, "status");
        String expected = "SUSPENDED".equals(target) ? "ACTIVE" : "SUSPENDED";
        if (!expected.equals(from)) {
            throw ApiException.conflict("invalid_transition", "Cannot change a " + from + " corridor to " + target);
        }
        ObjectNode v = rows.object().put("status", target);
        if (reason != null) {
            v.put("status_reason", reason);
        } else {
            v.putNull("status_reason");
        }
        ObjectNode row = rows.update(CORRIDOR, id, v);
        events.publish("payments.remittance_corridor.status_changed", CORRIDOR, row);
        return row;
    }
}
