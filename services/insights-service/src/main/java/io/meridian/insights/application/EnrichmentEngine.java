package io.meridian.insights.application;

import static io.meridian.insights.application.Json.text;

import io.meridian.insights.domain.MatchRules;
import io.meridian.insights.domain.MerchantNameCleaner;
import io.meridian.insights.infrastructure.Rows;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

/**
 * Merchant and category resolution shared by enrichment, re-categorisation and the rule tester.
 *
 * <p>Merchant: the first active merchant rule (priority, then id) whose pattern matches the raw descriptor gives the
 * clean name, logo, website, merchant id and fallback MCC; otherwise the heuristic {@link MerchantNameCleaner}.
 * Category precedence: the customer's active override rules, then the merchant rule's category, then the MCC
 * mapping of the taxonomy (most specific level first), then the income category for inflows, then UNCATEGORISED.
 */
@Component
public class EnrichmentEngine {
    private static final Logger log = LoggerFactory.getLogger(EnrichmentEngine.class);
    public static final String MODEL_VERSION = "rules-1";

    private final Rows rows;

    public EnrichmentEngine(Rows rows) {
        this.rows = rows;
    }

    /** Resolution result as column values of transaction_enrichment. */
    public ObjectNode resolve(UUID customerId, String raw, String mcc, BigDecimal amount) {
        ObjectNode out = rows.object();
        List<ObjectNode> ruleRows = rows.query("select row_to_json(t)::text from merchant_rule t where t.is_active");
        Map<UUID, ObjectNode> byId = ruleRows.stream().collect(Collectors.toMap(r -> Json.uuid(r, "id"), Function.identity()));
        List<MatchRules.MerchantRule> rules = ruleRows.stream().map(r -> new MatchRules.MerchantRule(Json.uuid(r, "id"),
                text(r, "match_type"), text(r, "pattern"), r.path("priority").asInt(100))).toList();
        Optional<MatchRules.MerchantRule> hit;
        try {
            hit = MatchRules.firstMerchantRule(rules, raw);
        } catch (RuntimeException e) {
            log.warn("merchant rule evaluation failed: {}", e.getMessage());
            hit = Optional.empty();
        }
        ObjectNode rule = hit.map(h -> byId.get(h.id())).orElse(null);
        String effectiveMcc = mcc != null && !mcc.isBlank() ? mcc : (rule == null ? null : text(rule, "mcc"));
        String cleanName = rule != null ? text(rule, "clean_name") : MerchantNameCleaner.clean(raw);
        put(out, "merchant_clean_name", cleanName);
        put(out, "mcc", effectiveMcc);
        if (rule != null) {
            put(out, "merchant_rule_id", text(rule, "id"));
            put(out, "merchant_logo_url", text(rule, "logo_url"));
            put(out, "merchant_website", text(rule, "website"));
            put(out, "merchant_id", text(rule, "merchant_id"));
        }
        // Category precedence.
        if (customerId != null) {
            Optional<ObjectNode> override = override(customerId, cleanName, raw, amount);
            if (override.isPresent()) {
                category(out, text(override.get(), "spend_category_id"), "OVERRIDE_RULE", "1.0");
                put(out, "category_override_rule_id", text(override.get(), "id"));
                return out;
            }
        }
        if (rule != null && text(rule, "spend_category_id") != null) {
            category(out, text(rule, "spend_category_id"), "MERCHANT_RULE", "0.95");
            return out;
        }
        if (effectiveMcc != null) {
            Optional<String> byMcc = rows.scalar("""
                    select id::text from spend_category where is_active and mcc_codes @> jsonb_build_array(?::text)
                    order by case level when 'SUBCATEGORY' then 0 when 'CATEGORY' then 1 else 2 end, code limit 1""",
                    String.class, effectiveMcc);
            if (byMcc.isPresent()) {
                category(out, byMcc.get(), "MCC", "0.8");
                return out;
            }
        }
        if (amount != null && amount.signum() > 0) {
            Optional<String> income = rows.scalar("""
                    select id::text from spend_category where is_active and is_income
                    order by (code = 'INCOME') desc, case level when 'GROUP' then 0 else 1 end, code limit 1""", String.class);
            if (income.isPresent()) {
                category(out, income.get(), "INCOME", "0.6");
                return out;
            }
        }
        Optional<String> dflt = rows.scalar("select id::text from spend_category where code = 'UNCATEGORISED' and is_active",
                String.class);
        category(out, dflt.orElse(null), "DEFAULT", dflt.isPresent() ? "0.1" : "0");
        return out;
    }

    /** The first active override rule of the customer matching the transaction. */
    public Optional<ObjectNode> override(UUID customerId, String cleanName, String raw, BigDecimal amount) {
        List<ObjectNode> ruleRows = rows.query("""
                select row_to_json(t)::text from category_override_rule t where t.customer_id = ? and t.is_active""", customerId);
        List<MatchRules.OverrideRule> rules = new ArrayList<>();
        for (ObjectNode r : ruleRows) {
            rules.add(overrideRule(r));
        }
        try {
            return MatchRules.firstOverride(rules, cleanName, raw, amount).flatMap(m ->
                    ruleRows.stream().filter(r -> m.id().equals(Json.uuid(r, "id"))).findFirst());
        } catch (RuntimeException e) {
            log.warn("override rule evaluation failed for customer {}: {}", customerId, e.getMessage());
            return Optional.empty();
        }
    }

    public static MatchRules.OverrideRule overrideRule(ObjectNode r) {
        return new MatchRules.OverrideRule(Json.uuid(r, "id"), text(r, "match_merchant_name"),
                text(r, "match_description_pattern"), Json.decimal(r, "match_amount_min"), Json.decimal(r, "match_amount_max"),
                r.path("priority").asInt(100));
    }

    private static void category(ObjectNode out, String id, String source, String confidence) {
        put(out, "spend_category_id", id);
        out.put("category_source", source);
        out.put("category_confidence", confidence);
    }

    private static void put(ObjectNode out, String field, String value) {
        if (value == null) {
            out.putNull(field);
        } else {
            out.put(field, value);
        }
    }
}
