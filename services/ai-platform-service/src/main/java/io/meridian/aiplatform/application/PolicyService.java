package io.meridian.aiplatform.application;

import io.meridian.aiplatform.domain.PiiRedactor;
import io.meridian.aiplatform.infrastructure.Store;
import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * LLM gateway policies: validation (models, redaction entities and safe tenant patterns, token caps, budgets),
 * redaction as the gateway applies it, and monthly usage accounting against the token budget.
 */
@Service
public class PolicyService {
    private final Store store;
    private final JsonMapper json;

    public PolicyService(Store store, JsonMapper json) {
        this.store = store;
        this.json = json;
    }

    public void prepare(ObjectNode values, JsonNode existing) {
        JsonNode merged = existing == null ? values : ((ObjectNode) existing.deepCopy()).setAll(values);
        JsonNode models = merged.get("allowed_models");
        if (models == null || !models.isArray() || models.isEmpty()) {
            throw Fields.invalid("allowed_models", "required", "allowed_models must list at least one model alias");
        }
        patterns(merged.get("redaction_patterns"));
        Integer max = Fields.integer(merged, "max_tokens_per_request");
        if (max != null && (max < 1 || max > 1_000_000)) {
            throw Fields.invalid("max_tokens_per_request", "out_of_range", "max_tokens_per_request must be 1-1000000");
        }
        BigDecimal budget = Fields.decimal(merged, "monthly_token_budget");
        if (budget != null && budget.signum() < 0) {
            throw Fields.invalid("monthly_token_budget", "out_of_range", "monthly_token_budget cannot be negative");
        }
        Integer retention = Fields.integer(merged, "retention_days");
        if (retention != null && (retention < 1 || retention > 3650)) {
            throw Fields.invalid("retention_days", "out_of_range", "retention_days must be 1-3650");
        }
        BigDecimal price = Fields.decimal(merged, "cost_per_1k_tokens");
        if (price != null && (price.signum() < 0 || Fields.text(merged, "cost_currency") == null)) {
            throw Fields.invalid("cost_per_1k_tokens", "invalid_price", "cost_per_1k_tokens must be >= 0 with a cost_currency");
        }
    }

    public List<PiiRedactor.CustomPattern> patterns(JsonNode raw) {
        if (raw == null || raw.isNull()) {
            return List.of();
        }
        List<String> errors = new ArrayList<>();
        List<PiiRedactor.CustomPattern> out = PiiRedactor.compile(
                json.convertValue(raw, new TypeReference<List<Map<String, Object>>>() {}), errors);
        if (!errors.isEmpty()) {
            throw new ApiException(422, "invalid_redaction_pattern", errors.getFirst(),
                    errors.stream().map(e -> new ApiException.FieldError("redaction_patterns", "invalid", e)).toList());
        }
        return out;
    }

    /** Redacts text as the gateway would under this policy (no redaction when pii_redaction is off). */
    public PiiRedactor.Result redact(JsonNode policy, String text) {
        if (policy != null && !policy.path("pii_redaction").asBoolean(true)) {
            return new PiiRedactor.Result(text, Map.of());
        }
        List<String> entities = new ArrayList<>();
        if (policy != null && policy.path("redaction_entities").isArray()) {
            policy.get("redaction_entities").forEach(e -> entities.add(e.asString()));
        }
        return PiiRedactor.redact(text, PiiRedactor.entities(entities), patterns(policy == null ? null : policy.get("redaction_patterns")));
    }

    public ObjectNode preview(UUID id, String text) {
        JsonNode p = store.require("llm_gateway_policy", id);
        PiiRedactor.Result r = redact(p, text);
        ObjectNode out = json.createObjectNode();
        out.put("policy_id", id.toString());
        out.put("redacted", r.text());
        out.set("counts", json.valueToTree(r.counts()));
        out.put("total", r.total());
        out.put("pii_redaction", p.path("pii_redaction").asBoolean(true));
        return out;
    }

    /** Tokens used this month by copilots under the policy. */
    public long usedThisMonth(UUID policyId) {
        return store.scalar("""
                select coalesce(sum(coalesce(i.input_tokens, 0) + coalesce(i.output_tokens, 0)), 0)::bigint
                from copilot_interaction i join copilot_definition c on c.id = i.copilot_definition_id
                where c.llm_gateway_policy_id = ? and i.created_at >= date_trunc('month', now())""", Long.class, policyId).orElse(0L);
    }

    public ObjectNode usage(UUID id, String month) {
        JsonNode p = store.require("llm_gateway_policy", id);
        YearMonth ym;
        if (month == null) {
            ym = YearMonth.from(store.scalar("select current_date", LocalDate.class).orElseThrow());
        } else {
            try {
                ym = YearMonth.parse(month);
            } catch (DateTimeParseException e) {
                throw Fields.invalid("month", "invalid_month", "month must be YYYY-MM");
            }
        }
        LocalDate from = ym.atDay(1);
        LocalDate to = ym.plusMonths(1).atDay(1);
        JsonNode totals = store.row("""
                select json_build_object('interactions', count(*), 'input_tokens', coalesce(sum(i.input_tokens), 0),
                       'output_tokens', coalesce(sum(i.output_tokens), 0), 'cost', coalesce(sum(i.cost), 0),
                       'refused', count(*) filter (where i.outcome = 'REFUSED'))::text
                from copilot_interaction i join copilot_definition c on c.id = i.copilot_definition_id
                where c.llm_gateway_policy_id = ? and i.created_at >= ? and i.created_at < ?""",
                id, from.atStartOfDay().atOffset(java.time.ZoneOffset.UTC), to.atStartOfDay().atOffset(java.time.ZoneOffset.UTC))
                .orElseThrow();
        long input = totals.get("input_tokens").asLong();
        long output = totals.get("output_tokens").asLong();
        ObjectNode out = json.createObjectNode();
        out.put("policy_id", id.toString());
        out.put("month", ym.toString());
        out.put("interactions", totals.get("interactions").asLong());
        out.put("refused", totals.get("refused").asLong());
        out.put("input_tokens", input);
        out.put("output_tokens", output);
        out.put("total_tokens", input + output);
        out.put("cost", new BigDecimal(totals.get("cost").asString()).setScale(4, java.math.RoundingMode.HALF_EVEN).toPlainString());
        out.put("cost_currency", Fields.text(p, "cost_currency"));
        if (p.hasNonNull("monthly_token_budget")) {
            long budget = p.get("monthly_token_budget").asLong();
            out.put("monthly_token_budget", budget);
            out.put("remaining_tokens", Math.max(0, budget - input - output));
            out.put("utilisation_percent", budget == 0 ? "100.00" : BigDecimal.valueOf(input + output).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(budget), 2, java.math.RoundingMode.HALF_EVEN).toPlainString());
            out.put("budget_exceeded", input + output >= budget);
        }
        ArrayNode byCopilot = out.putArray("by_copilot");
        store.rows("""
                select json_build_object('copilot_definition_id', c.id, 'code', c.code, 'interactions', count(i.id),
                       'tokens', coalesce(sum(coalesce(i.input_tokens, 0) + coalesce(i.output_tokens, 0)), 0))::text
                from copilot_definition c join copilot_interaction i on i.copilot_definition_id = c.id
                where c.llm_gateway_policy_id = ? and i.created_at >= ? and i.created_at < ?
                group by c.id, c.code order by c.code""",
                id, from.atStartOfDay().atOffset(java.time.ZoneOffset.UTC), to.atStartOfDay().atOffset(java.time.ZoneOffset.UTC))
                .forEach(byCopilot::add);
        ArrayNode byModel = out.putArray("by_model");
        store.rows("""
                select json_build_object('model_name', i.model_name, 'interactions', count(*),
                       'tokens', coalesce(sum(coalesce(i.input_tokens, 0) + coalesce(i.output_tokens, 0)), 0))::text
                from copilot_interaction i join copilot_definition c on c.id = i.copilot_definition_id
                where c.llm_gateway_policy_id = ? and i.created_at >= ? and i.created_at < ?
                group by i.model_name order by i.model_name""",
                id, from.atStartOfDay().atOffset(java.time.ZoneOffset.UTC), to.atStartOfDay().atOffset(java.time.ZoneOffset.UTC))
                .forEach(byModel::add);
        return out;
    }
}
