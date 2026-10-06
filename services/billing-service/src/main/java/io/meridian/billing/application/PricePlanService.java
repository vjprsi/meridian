package io.meridian.billing.application;

import io.meridian.billing.domain.BillingStates;
import io.meridian.billing.domain.PricingException;
import io.meridian.billing.domain.UsageMetrics;
import io.meridian.billing.infrastructure.BillingStore;
import io.meridian.common.error.ApiException;
import io.meridian.events.OutboxWriter;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Price plans (platform-wide catalogue, not tenant scoped). Lifecycle DRAFT -> ACTIVE (/publish) -> RETIRED
 * (/retire); status is read-only in create/PATCH. Only drafts can be edited; published plans may only change
 * valid_to.
 */
@Service
public class PricePlanService {
    static final String EVENT_PUBLISHED = "platform.price_plan.published";
    static final String EVENT_RETIRED = "platform.price_plan.retired";
    private static final Set<String> EDITABLE_WHEN_PUBLISHED = Set.of("valid_to");

    private final CrudEngine crud;
    private final BillingStore store;
    private final OutboxWriter outbox;
    private final JsonMapper json;

    public PricePlanService(CrudEngine crud, BillingStore store, OutboxWriter outbox, JsonMapper json) {
        this.crud = crud;
        this.store = store;
        this.outbox = outbox;
        this.json = json;
    }

    @Transactional
    public String create(Object body) {
        ObjectNode values = crud.toColumns("price_plan", body);
        values.put("status", "DRAFT");
        validate(values);
        return crud.insert("price_plan", values);
    }

    @Transactional
    public String update(UUID id, int expectedVersion, Object body) {
        JsonNode current = json.readTree(store.lockPlan(id));
        ObjectNode changes = crud.toColumns("price_plan", body);
        String from = Rows.text(current, "status");
        if (!"DRAFT".equals(from)) {
            for (String f : changes.propertyNames()) {
                if (!EDITABLE_WHEN_PUBLISHED.contains(f) && !Rows.same(changes.get(f), current.get(f))) {
                    throw ApiException.conflict("plan_not_editable",
                            "Price plan is " + from + "; only valid_to can change (create a new plan instead)");
                }
            }
        }
        ObjectNode merged = ((ObjectNode) current.deepCopy()).setAll(changes);
        validate(merged);
        if (Rows.int0(current, "row_version") != expectedVersion) {
            throw ApiException.conflict("version_conflict",
                    "If-Match " + expectedVersion + " does not match the current version; reload and retry");
        }
        return crud.updateRow("price_plan", Map.of("id", id), changes, expectedVersion)
                .orElseThrow(() -> ApiException.conflict("version_conflict", "Price plan changed concurrently"));
    }

    /** DRAFT -> ACTIVE: the plan becomes available for subscriptions. */
    @Transactional
    public String publish(UUID id) {
        JsonNode current = json.readTree(store.lockPlan(id));
        transition(current, "ACTIVE");
        validate(current);
        String row = crud.updateRow("price_plan", Map.of("id", id), json.createObjectNode().put("status", "ACTIVE"), null)
                .orElseThrow();
        outbox.publish(EVENT_PUBLISHED, "price_plan", id, json.readTree(row));
        return row;
    }

    /** DRAFT/ACTIVE -> RETIRED: withdrawn from new subscriptions (existing subscriptions keep it). */
    @Transactional
    public String retire(UUID id, String reason) {
        JsonNode current = json.readTree(store.lockPlan(id));
        transition(current, "RETIRED");
        String row = crud.updateRow("price_plan", Map.of("id", id), json.createObjectNode().put("status", "RETIRED").put("retire_reason", reason)
                        .put("retired_at", java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).toString()), null)
                .orElseThrow();
        outbox.publish(EVENT_RETIRED, "price_plan", id, json.readTree(row));
        return row;
    }

    private static void transition(JsonNode plan, String to) {
        try {
            BillingStates.require(BillingStates.PRICE_PLAN, "Price plan", Rows.text(plan, "status"), to);
        } catch (BillingStates.TransitionException e) {
            throw ApiException.conflict("invalid_transition", e.getMessage());
        }
    }

    private void validate(JsonNode plan) {
        for (String f : new String[] {"platform_fee", "price_per_active_account"}) {
            BigDecimal v = Rows.decimal(plan, f);
            if (v != null && v.signum() < 0) {
                throw ApiException.unprocessable("invalid_price", f + " must not be negative");
            }
        }
        if (Rows.longOr(plan, "included_active_accounts", 0) < 0) {
            throw ApiException.unprocessable("invalid_price", "included_active_accounts must not be negative");
        }
        LocalDate from = Rows.date(plan, "valid_from");
        LocalDate to = Rows.date(plan, "valid_to");
        if (from != null && to != null && to.isBefore(from)) {
            throw ApiException.unprocessable("invalid_validity", "valid_to must not be before valid_from");
        }
        String ccy = Rows.text(plan, "currency");
        if (ccy != null && !ccy.matches("[A-Z]{3}")) {
            throw ApiException.unprocessable("invalid_currency", "currency must be ISO 4217 alpha-3");
        }
        validateMetricPrices(plan.get("metric_prices"), json);
    }

    /** Validates a metric_prices object (keys are UsageMetric values, values tiered prices). */
    static void validateMetricPrices(JsonNode metricPrices, JsonMapper json) {
        if (metricPrices == null || metricPrices.isNull()) {
            return;
        }
        if (!metricPrices.isObject()) {
            throw ApiException.unprocessable("invalid_metric_prices", "metric_prices must be an object keyed by usage metric");
        }
        try {
            UsageMetrics.parsePrices(json.convertValue(metricPrices, Map.class));
        } catch (PricingException e) {
            throw ApiException.unprocessable("invalid_metric_prices", e.getMessage());
        }
    }
}
