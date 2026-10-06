package io.meridian.insights.application;

import static io.meridian.insights.application.Json.text;

import io.meridian.common.error.ApiException;
import io.meridian.common.error.ApiException.FieldError;
import io.meridian.insights.domain.MatchRules;
import io.meridian.insights.infrastructure.Rows;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Customer category override rules. A rule needs at least one criterion (merchant name, description regex or amount
 * range); applying it re-categorises the customer's current enrichments that match (manual re-categorisations by the
 * customer are kept) and recomputes their budgets.
 */
@Service
public class OverrideRuleService {
    static final String T = "category_override_rule";

    private final Rows rows;
    private final CrudEngine crud;
    private final EnrichmentService enrichments;
    private final BudgetService budgets;

    public OverrideRuleService(Rows rows, CrudEngine crud, EnrichmentService enrichments, BudgetService budgets) {
        this.rows = rows;
        this.crud = crud;
        this.enrichments = enrichments;
        this.budgets = budgets;
    }

    @Transactional
    public ObjectNode create(Object body) {
        return createInternal(crud.toColumns(T, body));
    }

    ObjectNode createInternal(ObjectNode v) {
        validate(v);
        ObjectNode row = rows.insert(T, v);
        if (row.path("apply_to_past").asBoolean(true) && row.path("is_active").asBoolean(true)) {
            apply(row);
            return rows.get(T, Json.uuid(row, "id"));
        }
        return row;
    }

    @Transactional
    public ObjectNode update(UUID id, Object body, int expectedVersion) {
        ObjectNode current = rows.lock(T, id);
        ObjectNode changes = crud.toColumns(T, body);
        if (changes.has("customer_id") && !text(changes, "customer_id").equals(text(current, "customer_id"))) {
            throw ApiException.unprocessable("immutable_field", "customer_id cannot change");
        }
        ObjectNode merged = current.deepCopy();
        merged.setAll(changes);
        validate(merged);
        return crud.updateRow(T, Map.of("id", id), changes, expectedVersion).map(rows::tree)
                .orElseThrow(() -> Json.versionConflict(expectedVersion));
    }

    @Transactional
    public JsonNode apply(UUID id) {
        ObjectNode rule = rows.lock(T, id);
        if (!rule.path("is_active").asBoolean(true)) {
            throw ApiException.conflict("rule_inactive", "The rule is inactive");
        }
        return apply(rule);
    }

    private ObjectNode apply(ObjectNode rule) {
        UUID ruleId = Json.uuid(rule, "id");
        UUID customer = Json.uuid(rule, "customer_id");
        UUID category = Json.uuid(rule, "spend_category_id");
        MatchRules.OverrideRule m = EnrichmentEngine.overrideRule(rule);
        int matched = 0;
        int changed = 0;
        for (ObjectNode e : rows.query("select row_to_json(t)::text from transaction_enrichment t where t.customer_id = ? and "
                + EnrichmentService.CURRENT + " order by t.transaction_at", customer)) {
            if (!m.matches(text(e, "merchant_clean_name"), text(e, "raw_description"), Json.decimal(e, "amount"))) {
                continue;
            }
            matched++;
            if ("CUSTOMER".equals(text(e, "category_source")) || category.toString().equals(text(e, "spend_category_id"))) {
                continue;
            }
            enrichments.supersede(e, category, "OVERRIDE_RULE", ruleId);
            changed++;
        }
        ObjectNode v = rows.object();
        v.put("applied_count", rule.path("applied_count").asInt(0) + changed);
        rows.update(T, ruleId, v);
        if (changed > 0) {
            budgets.recomputeCustomer(customer);
        }
        ObjectNode out = rows.object();
        out.put("category_override_rule_id", ruleId.toString());
        out.put("customer_id", customer.toString());
        out.put("matched", matched);
        out.put("recategorised", changed);
        out.put("applied_count", rule.path("applied_count").asInt(0) + changed);
        return out;
    }

    private void validate(ObjectNode v) {
        List<FieldError> errors = new ArrayList<>();
        MatchRules.OverrideRule r = EnrichmentEngine.overrideRule(v);
        if (!r.hasCriteria()) {
            errors.add(new FieldError("match_merchant_name", "criteria_required",
                    "Give a merchant name, a description pattern or an amount range"));
        }
        if (r.descriptionPattern() != null && !r.descriptionPattern().isBlank()) {
            String err = MatchRules.regexError(r.descriptionPattern());
            if (err != null) {
                errors.add(new FieldError("match_description_pattern", "invalid_regex", err));
            }
        }
        BigDecimal min = r.minAmount();
        BigDecimal max = r.maxAmount();
        if ((min != null && min.signum() < 0) || (max != null && max.signum() < 0)) {
            errors.add(new FieldError("match_amount_min", "must_not_be_negative", "Amount bounds are absolute amounts"));
        }
        if (min != null && max != null && min.compareTo(max) > 0) {
            errors.add(new FieldError("match_amount_max", "invalid_range", "match_amount_max must be >= match_amount_min"));
        }
        UUID category = Json.uuid(v, "spend_category_id");
        if (category == null || rows.scalar("select 1 from spend_category where id = ? and is_active", Integer.class,
                category).isEmpty()) {
            errors.add(new FieldError("spend_category_id", "not_found", "Active category " + category + " does not exist"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(422, "invalid_category_rule", "Category rule is invalid", errors);
        }
    }
}
