package io.meridian.insights.application;

import static io.meridian.insights.application.Json.text;

import io.meridian.common.error.ApiException;
import io.meridian.common.error.ApiException.FieldError;
import io.meridian.insights.api.generated.model.TransactionEnrichment;
import io.meridian.insights.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Transaction enrichment. Enrichment rows are append-only: a re-categorisation writes a new row that supersedes the
 * previous one, and the "current" enrichment of a source transaction is the one nobody supersedes. Enriching the
 * same source transaction twice returns the current enrichment (idempotent per source).
 */
@Service
public class EnrichmentService {
    static final String T = "transaction_enrichment";
    static final String CURRENT = "not exists (select 1 from transaction_enrichment s where s.supersedes_id = t.id)";
    private static final Set<String> COPY_EXCLUDED = Set.of("id", "tenant_id", "created_at", "created_by");

    private final Rows rows;
    private final Events events;
    private final EnrichmentEngine engine;
    private final RecurringService recurring;
    private final BudgetService budgets;

    public EnrichmentService(Rows rows, Events events, EnrichmentEngine engine, RecurringService recurring,
                             BudgetService budgets) {
        this.rows = rows;
        this.events = events;
        this.engine = engine;
        this.recurring = recurring;
        this.budgets = budgets;
    }

    @Transactional
    public ObjectNode enrich(JsonNode req) {
        UUID customer = Json.uuid(req, "customer_id");
        String sourceType = Json.required(req, "source_type");
        UUID sourceId = Json.uuid(req, "source_id");
        BigDecimal amount = Json.decimal(req, "amount");
        String currency = text(req, "currency");
        List<FieldError> errors = new ArrayList<>();
        if (customer == null) {
            errors.add(new FieldError("customer_id", "required", "customer_id is required"));
        }
        if (sourceId == null) {
            errors.add(new FieldError("source_id", "required", "source_id is required"));
        }
        if (amount == null || amount.signum() == 0) {
            errors.add(new FieldError("amount", "must_not_be_zero", "amount must be a non-zero signed amount (negative = spend)"));
        }
        if (currency == null) {
            errors.add(new FieldError("currency", "required", "currency is required"));
        }
        String mcc = text(req, "mcc");
        if (mcc != null && !mcc.matches("\\d{4}")) {
            errors.add(new FieldError("mcc", "invalid_mcc", "MCC must be four digits"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(422, "invalid_transaction", "Transaction cannot be enriched", errors);
        }
        rows.advisoryLock("enrich:" + sourceType + ":" + sourceId);
        Optional<ObjectNode> existing = current(sourceType, sourceId);
        if (existing.isPresent()) {
            if (!customer.toString().equals(text(existing.get(), "customer_id"))) {
                throw ApiException.conflict("source_customer_mismatch",
                        "Source transaction " + sourceId + " is already enriched for another customer");
            }
            return existing.get();
        }
        OffsetDateTime at = Json.ts(req, "transaction_at");
        if (at == null) {
            at = rows.now();
        }
        at = at.withOffsetSameInstant(ZoneOffset.UTC);
        String raw = text(req, "raw_description");
        ObjectNode v = engine.resolve(customer, raw, mcc, amount);
        v.put("customer_id", customer.toString());
        v.put("source_type", sourceType);
        v.put("source_id", sourceId.toString());
        copy(req, v, "account_ref", "raw_description", "geo_city", "geo_country");
        if (text(req, "merchant_id") != null) {
            v.put("merchant_id", text(req, "merchant_id"));
        }
        v.put("transaction_at", at.toString());
        v.put("amount", amount.toPlainString());
        v.put("currency", currency);
        v.put("enrichment_provider", "INTERNAL");
        v.put("model_version", EnrichmentEngine.MODEL_VERSION);
        Optional<ObjectNode> series = recurring.link(customer, text(v, "merchant_clean_name"), currency, amount, at);
        if (series.isPresent()) {
            v.put("is_recurring", true);
            v.put("recurring_payment_id", text(series.get(), "id"));
        }
        ObjectNode row = rows.insert(T, v);
        events.publish("engagement.transaction.enriched", T, row, TransactionEnrichment.class);
        if (amount.signum() < 0) {
            budgets.onSpend(customer, currency, at.toLocalDate());
        }
        return row;
    }

    public Optional<ObjectNode> current(String sourceType, UUID sourceId) {
        return rows.queryOne("select row_to_json(t)::text from transaction_enrichment t where t.source_type = ? and t.source_id = ? and "
                + CURRENT + " order by t.created_at desc, t.id desc limit 1", sourceType, sourceId);
    }

    /** Customer or agent re-categorisation: a superseding row (and, on request, an override rule for the merchant). */
    @Transactional
    public ObjectNode recategorise(UUID id, UUID categoryId, boolean createRule, OverrideRuleService rules) {
        ObjectNode old = rows.get(T, id);
        rows.advisoryLock("enrich:" + text(old, "source_type") + ":" + text(old, "source_id"));
        if (rows.scalar("select 1 from transaction_enrichment where supersedes_id = ?", Integer.class, id).isPresent()) {
            throw ApiException.conflict("enrichment_superseded", "Enrichment " + id + " was superseded; re-categorise the current one");
        }
        if (rows.scalar("select 1 from spend_category where id = ? and is_active", Integer.class, categoryId).isEmpty()) {
            throw ApiException.unprocessable("category_not_found", "Active category " + categoryId + " does not exist");
        }
        ObjectNode row = supersede(old, categoryId, "CUSTOMER", null);
        if (createRule) {
            String merchant = text(old, "merchant_clean_name");
            if (merchant == null) {
                throw ApiException.unprocessable("merchant_unknown", "The transaction has no merchant name to build a rule on");
            }
            ObjectNode rule = rows.object();
            rule.put("customer_id", text(old, "customer_id"));
            rule.put("match_merchant_name", merchant);
            rule.put("spend_category_id", categoryId.toString());
            rule.put("apply_to_past", true);
            rules.createInternal(rule);
        }
        budgets.recomputeCustomer(Json.uuid(old, "customer_id"));
        return row;
    }

    /** Writes the superseding row of {@code old} with a new category. */
    ObjectNode supersede(ObjectNode old, UUID categoryId, String source, UUID overrideRuleId) {
        ObjectNode v = rows.object();
        old.propertyNames().forEach(n -> {
            if (!COPY_EXCLUDED.contains(n)) {
                v.set(n, old.get(n));
            }
        });
        v.put("spend_category_id", categoryId.toString());
        v.put("category_source", source);
        v.put("category_confidence", "1.0");
        v.put("enrichment_provider", "CUSTOMER".equals(source) ? "CUSTOMER" : "INTERNAL");
        if (overrideRuleId == null) {
            v.putNull("category_override_rule_id");
        } else {
            v.put("category_override_rule_id", overrideRuleId.toString());
        }
        v.put("supersedes_id", text(old, "id"));
        ObjectNode row = rows.insert(T, v);
        events.publish("engagement.transaction.recategorised", T, row, TransactionEnrichment.class);
        return row;
    }

    private static void copy(JsonNode from, ObjectNode to, String... fields) {
        for (String f : fields) {
            if (text(from, f) != null) {
                to.put(f, text(from, f));
            }
        }
    }
}
