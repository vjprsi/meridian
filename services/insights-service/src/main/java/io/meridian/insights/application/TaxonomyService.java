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
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Category taxonomy (global, shared by all tenants) and the tenant's merchant clean-name rules, with validation:
 * GROUP categories are roots, a CATEGORY sits under a GROUP and a SUBCATEGORY under a CATEGORY; MCC codes are four
 * digits; merchant rule regular expressions must compile.
 */
@Service
public class TaxonomyService {
    static final String CATEGORY = "spend_category";
    static final String MERCHANT_RULE = "merchant_rule";
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]*(\\.[A-Z0-9_]+)*");
    private static final Pattern MCC = Pattern.compile("\\d{4}");

    private final Rows rows;
    private final CrudEngine crud;
    private final EnrichmentEngine engine;

    public TaxonomyService(Rows rows, CrudEngine crud, EnrichmentEngine engine) {
        this.rows = rows;
        this.crud = crud;
        this.engine = engine;
    }

    // ------------------------------------------------------------------ categories

    @Transactional
    public ObjectNode createCategory(Object body) {
        ObjectNode v = crud.toColumns(CATEGORY, body);
        if (text(v, "code") != null) {
            v.put("code", text(v, "code").trim().toUpperCase());
        }
        validateCategory(v, null);
        if (rows.scalar("select 1 from spend_category where code = ?", Integer.class, text(v, "code")).isPresent()) {
            throw ApiException.conflict("duplicate_code", "Category code " + text(v, "code") + " already exists");
        }
        return rows.insert(CATEGORY, v);
    }

    @Transactional
    public ObjectNode updateCategory(UUID id, Object body, int expectedVersion) {
        ObjectNode current = rows.lock(CATEGORY, id);
        ObjectNode changes = crud.toColumns(CATEGORY, body);
        if (changes.has("code") && !text(changes, "code").equalsIgnoreCase(text(current, "code"))) {
            throw ApiException.unprocessable("immutable_field", "A category code cannot change (enrichments and budgets refer to it)");
        }
        changes.remove("code");
        ObjectNode merged = current.deepCopy();
        merged.setAll(changes);
        validateCategory(merged, id);
        return crud.updateRow(CATEGORY, Map.of("id", id), changes, expectedVersion)
                .map(rows::tree).orElseThrow(() -> Json.versionConflict(expectedVersion));
    }

    private void validateCategory(ObjectNode v, UUID self) {
        List<FieldError> errors = new ArrayList<>();
        String code = text(v, "code");
        if (code == null || !CODE.matcher(code).matches()) {
            errors.add(new FieldError("code", "invalid_format", "Code must be upper case segments, e.g. FOOD.GROCERIES"));
        }
        String level = text(v, "level");
        UUID parent = Json.uuid(v, "parent_id");
        if ("GROUP".equals(level) && parent != null) {
            errors.add(new FieldError("parent_id", "not_allowed", "A GROUP category has no parent"));
        }
        if (!"GROUP".equals(level)) {
            if (parent == null) {
                errors.add(new FieldError("parent_id", "required", "A " + level + " needs a parent"));
            } else if (parent.equals(self)) {
                errors.add(new FieldError("parent_id", "invalid_parent", "A category cannot be its own parent"));
            } else {
                String parentLevel = rows.scalar("select level from spend_category where id = ?", String.class, parent).orElse(null);
                String expected = "CATEGORY".equals(level) ? "GROUP" : "CATEGORY";
                if (parentLevel == null) {
                    errors.add(new FieldError("parent_id", "not_found", "Parent category " + parent + " does not exist"));
                } else if (!expected.equals(parentLevel)) {
                    errors.add(new FieldError("parent_id", "invalid_parent", "A " + level + " must sit under a " + expected));
                }
            }
        }
        JsonNode mccs = v.get("mcc_codes");
        if (mccs != null && mccs.isArray()) {
            for (JsonNode m : mccs) {
                if (!MCC.matcher(m.asString()).matches()) {
                    errors.add(new FieldError("mcc_codes", "invalid_mcc", "MCC " + m.asString() + " is not four digits"));
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(422, "invalid_category", "Category is invalid", errors);
        }
    }

    // ------------------------------------------------------------------ merchant rules

    @Transactional
    public ObjectNode createMerchantRule(Object body) {
        ObjectNode v = crud.toColumns(MERCHANT_RULE, body);
        validateMerchantRule(v);
        return rows.insert(MERCHANT_RULE, v);
    }

    @Transactional
    public ObjectNode updateMerchantRule(UUID id, Object body, int expectedVersion) {
        ObjectNode current = rows.lock(MERCHANT_RULE, id);
        ObjectNode changes = crud.toColumns(MERCHANT_RULE, body);
        ObjectNode merged = current.deepCopy();
        merged.setAll(changes);
        validateMerchantRule(merged);
        return crud.updateRow(MERCHANT_RULE, Map.of("id", id), changes, expectedVersion)
                .map(rows::tree).orElseThrow(() -> Json.versionConflict(expectedVersion));
    }

    private void validateMerchantRule(ObjectNode v) {
        List<FieldError> errors = new ArrayList<>();
        String pattern = text(v, "pattern");
        if (pattern == null || pattern.isBlank()) {
            errors.add(new FieldError("pattern", "required", "pattern is required"));
        } else if ("REGEX".equals(text(v, "match_type"))) {
            String err = MatchRules.regexError(pattern);
            if (err != null) {
                errors.add(new FieldError("pattern", "invalid_regex", err));
            }
        }
        if (text(v, "clean_name") == null || text(v, "clean_name").isBlank()) {
            errors.add(new FieldError("clean_name", "required", "clean_name is required"));
        }
        if (text(v, "mcc") != null && !MCC.matcher(text(v, "mcc")).matches()) {
            errors.add(new FieldError("mcc", "invalid_mcc", "MCC must be four digits"));
        }
        UUID category = Json.uuid(v, "spend_category_id");
        if (category != null && rows.scalar("select 1 from spend_category where id = ?", Integer.class, category).isEmpty()) {
            errors.add(new FieldError("spend_category_id", "not_found", "Category " + category + " does not exist"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(422, "invalid_merchant_rule", "Merchant rule is invalid", errors);
        }
    }

    /** Runs the rules on a descriptor without side effects (no customer overrides). */
    @Transactional(readOnly = true)
    public JsonNode test(JsonNode req) {
        String raw = Json.required(req, "raw_description");
        String mcc = text(req, "mcc");
        BigDecimal amount = Json.decimal(req, "amount");
        ObjectNode r = engine.resolve(null, raw, mcc, amount);
        ObjectNode out = rows.object();
        out.put("raw_description", raw);
        out.setAll(r);
        UUID cat = Json.uuid(r, "spend_category_id");
        if (cat != null) {
            rows.queryOne("select row_to_json(t)::text from spend_category t where t.id = ?", cat).ifPresent(c -> {
                out.put("category_code", text(c, "code"));
                out.put("category_name", text(c, "name"));
            });
        }
        ArrayNode matching = out.putArray("matching_rules");
        for (ObjectNode rule : rows.query("""
                select row_to_json(t)::text from merchant_rule t where t.is_active order by t.priority, t.id""")) {
            MatchRules.MerchantRule m = new MatchRules.MerchantRule(Json.uuid(rule, "id"), text(rule, "match_type"),
                    text(rule, "pattern"), rule.path("priority").asInt(100));
            try {
                if (m.matches(raw)) {
                    matching.add(text(rule, "id"));
                }
            } catch (RuntimeException ignored) {
                // invalid stored expression: not a match
            }
        }
        return out;
    }

    /** Category ids of a category and all its descendants. */
    public List<UUID> descendants(UUID categoryId) {
        return rows.jdbc().sql("""
                with recursive c(id) as (select ?::uuid union all select s.id from spend_category s join c on s.parent_id = c.id)
                select id from c""").param(categoryId).query(UUID.class).list();
    }
}
