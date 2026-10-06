package io.meridian.config.application;

import io.meridian.common.error.ApiException;
import io.meridian.config.domain.TaxCalculator;
import io.meridian.config.infrastructure.ConfigStore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Tax rules (validated, evented) and tax calculation. */
@Service
public class TaxService {
    static final String EVENT = "platform.tax_rate_rule.changed";
    private final EventedCrud evented;
    private final ConfigStore store;
    private final JsonMapper json;

    public TaxService(EventedCrud evented, ConfigStore store, JsonMapper json) {
        this.evented = evented;
        this.store = store;
        this.json = json;
    }

    @Transactional
    public String create(Object body) {
        return evented.create("tax_rate_rule", body, TaxService::validate, EVENT);
    }

    @Transactional
    public String update(UUID id, Object body, String ifMatch) {
        return evented.update("tax_rate_rule", id, body, EventedCrud.parseIfMatch(ifMatch), TaxService::validate, EVENT);
    }

    @Transactional
    public void delete(UUID id) {
        evented.delete("tax_rate_rule", id, r -> { }, EVENT);
    }

    static void validate(JsonNode v) {
        try {
            TaxCalculator.requireValidRule(dec(v, "rate"), date(v, "valid_from"), date(v, "valid_to"),
                    dec(v, "threshold_amount"), v.hasNonNull("currency") ? v.get("currency").asString().trim() : null);
        } catch (NumberFormatException | java.time.format.DateTimeParseException e) {
            throw ApiException.badRequest("invalid_value", e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public JsonNode calculate(String country, String appliesTo, BigDecimal amount, String currency, String segment,
                              UUID legalEntityId) {
        LocalDate today = LocalDate.now();
        List<TaxCalculator.Rule> rules = store.taxRules(country, appliesTo, today).stream().map(json::readTree)
                .map(r -> new TaxCalculator.Rule(UUID.fromString(r.get("id").asString()), r.get("tax_type").asString(),
                        r.get("name").asString(), dec(r, "rate"), text(r, "customer_segment"),
                        r.hasNonNull("legal_entity_id") ? UUID.fromString(r.get("legal_entity_id").asString()) : null,
                        dec(r, "threshold_amount"), r.hasNonNull("currency") ? r.get("currency").asString().trim() : null,
                        text(r, "tax_gl_code"), date(r, "valid_from"), date(r, "valid_to")))
                .toList();
        TaxCalculator.Result result = TaxCalculator.calculate(rules, amount, currency, segment, legalEntityId, today);
        ObjectNode out = json.createObjectNode();
        out.put("country", country);
        out.put("applies_to", appliesTo);
        out.put("amount", amount.toPlainString());
        out.put("currency", currency);
        if (segment != null) {
            out.put("customer_segment", segment);
        }
        out.put("calculation_date", today.toString());
        ArrayNode lines = out.putArray("taxes");
        for (TaxCalculator.Line l : result.lines()) {
            ObjectNode o = lines.addObject();
            o.put("tax_rate_rule_id", l.rule().id().toString());
            o.put("tax_type", l.rule().taxType());
            o.put("name", l.rule().name());
            o.put("rate", l.rule().rate().stripTrailingZeros().toPlainString());
            o.put("taxable_amount", l.taxableAmount().toPlainString());
            o.put("tax_amount", l.tax().toPlainString());
            o.put("exempt", l.exempt());
            if (l.reason() != null) {
                o.put("reason", l.reason());
            }
            if (l.rule().glCode() != null) {
                o.put("tax_gl_code", l.rule().glCode());
            }
        }
        out.put("total_tax", result.totalTax().toPlainString());
        out.put("withholding_tax", result.withholding().toPlainString());
        out.put("net_amount", amount.subtract(result.withholding()).setScale(result.scale(), java.math.RoundingMode.HALF_EVEN).toPlainString());
        return out;
    }

    private static BigDecimal dec(JsonNode n, String f) {
        return n.hasNonNull(f) ? new BigDecimal(n.get(f).asString()) : null;
    }

    private static LocalDate date(JsonNode n, String f) {
        return n.hasNonNull(f) ? LocalDate.parse(n.get(f).asString()) : null;
    }

    private static String text(JsonNode n, String f) {
        return n.hasNonNull(f) ? n.get(f).asString() : null;
    }
}
