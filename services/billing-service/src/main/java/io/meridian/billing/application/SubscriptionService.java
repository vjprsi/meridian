package io.meridian.billing.application;

import io.meridian.billing.domain.BillingPeriods;
import io.meridian.billing.domain.BillingStates;
import io.meridian.billing.infrastructure.BillingStore;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tenant subscriptions: one open (non-cancelled) subscription per tenant, prorated plan changes, cancellation at
 * period end. Plan-change prorations are kept in the read-only {@code proration_adjustments} column and billed as
 * invoice lines for the period they fall in.
 */
@Service
public class SubscriptionService {
    static final String EVENT_CREATED = "platform.tenant_subscription.created";
    static final String EVENT_CHANGED = "platform.tenant_subscription.changed";
    static final String EVENT_PLAN_CHANGED = "platform.tenant_subscription.plan_changed";
    static final String EVENT_CANCELLED = "platform.tenant_subscription.cancelled";
    private static final Set<String> CUSTOM_PRICE_KEYS = Set.of("metric_prices", "platform_fee", "price_per_active_account");

    private final CrudEngine crud;
    private final BillingStore store;
    private final OutboxWriter outbox;
    private final JsonMapper json;

    public SubscriptionService(CrudEngine crud, BillingStore store, OutboxWriter outbox, JsonMapper json) {
        this.crud = crud;
        this.store = store;
        this.outbox = outbox;
        this.json = json;
    }

    @Transactional
    public String create(Object body) {
        ObjectNode values = crud.toColumns("tenant_subscription", body);
        String status = Rows.text(values, "status");
        if (status == null) {
            values.put("status", "TRIAL");
        } else if (!"TRIAL".equals(status) && !"ACTIVE".equals(status)) {
            throw ApiException.unprocessable("invalid_initial_status", "A subscription starts as TRIAL or ACTIVE");
        }
        UUID planId = Rows.uuid(values, "price_plan_id");
        JsonNode plan = activePlan(planId);
        if (!Rows.text(plan, "currency").equals(Rows.text(values, "currency"))) {
            throw ApiException.unprocessable("currency_mismatch",
                    "Subscription currency must equal the plan currency " + Rows.text(plan, "currency"));
        }
        LocalDate start = Rows.date(values, "start_date");
        LocalDate validFrom = Rows.date(plan, "valid_from");
        LocalDate validTo = Rows.date(plan, "valid_to");
        if (start.isBefore(validFrom) || (validTo != null && start.isAfter(validTo))) {
            throw ApiException.unprocessable("plan_not_available", "The plan is not available on " + start);
        }
        validateTerms(values);
        if (values.has("custom_prices")) {
            validateCustomPrices(values.get("custom_prices"));
        }
        store.lockTenant("subscription", TenantContext.require());
        if (store.hasOpenSubscription()) {
            throw ApiException.conflict("subscription_exists", "The tenant already has an open subscription; change its plan instead");
        }
        String row = crud.insert("tenant_subscription", values);
        publish(EVENT_CREATED, row);
        return row;
    }

    @Transactional
    public String update(UUID id, int expectedVersion, Object body) {
        JsonNode current = json.readTree(store.lockSubscription(id));
        ObjectNode changes = crud.toColumns("tenant_subscription", body);
        String from = Rows.text(current, "status");
        if ("CANCELLED".equals(from)) {
            throw ApiException.conflict("subscription_cancelled", "A cancelled subscription cannot be changed");
        }
        for (String f : new String[] {"price_plan_id", "currency", "start_date"}) {
            if (changes.has(f) && !Rows.same(changes.get(f), current.get(f))) {
                throw ApiException.unprocessable("immutable_field",
                        f + " cannot be changed by PATCH; use POST /change-plan to move plans");
            }
        }
        String to = Rows.text(changes, "status");
        boolean statusChange = to != null && !to.equals(from);
        if (statusChange) {
            if ("CANCELLED".equals(to)) {
                throw ApiException.conflict("invalid_transition", "Use POST /cancel to cancel a subscription");
            }
            try {
                BillingStates.require(BillingStates.SUBSCRIPTION, "Subscription", from, to);
            } catch (BillingStates.TransitionException e) {
                throw ApiException.conflict("invalid_transition", e.getMessage());
            }
        }
        validateTerms(((ObjectNode) current.deepCopy()).setAll(changes));
        if (changes.hasNonNull("custom_prices")) {
            validateCustomPrices(changes.get("custom_prices"));
        }
        if (Rows.int0(current, "row_version") != expectedVersion) {
            throw ApiException.conflict("version_conflict",
                    "If-Match " + expectedVersion + " does not match the current version; reload and retry");
        }
        String row = crud.updateRow("tenant_subscription", Map.of("id", id), changes, expectedVersion)
                .orElseThrow(() -> ApiException.conflict("version_conflict", "Subscription changed concurrently"));
        if (statusChange) {
            publish(EVENT_CHANGED, row);
        }
        return row;
    }

    /**
     * Moves the subscription to another plan from {@code effective}. The platform-fee difference for the rest of
     * the billing period containing that date is recorded as a proration adjustment (charge or credit).
     */
    @Transactional
    public String changePlan(UUID id, UUID newPlanId, LocalDate effective) {
        JsonNode sub = json.readTree(store.lockSubscription(id));
        if ("CANCELLED".equals(Rows.text(sub, "status"))) {
            throw ApiException.conflict("subscription_cancelled", "A cancelled subscription cannot change plan");
        }
        UUID oldPlanId = Rows.uuid(sub, "price_plan_id");
        if (oldPlanId.equals(newPlanId)) {
            throw ApiException.unprocessable("same_plan", "The subscription is already on this plan");
        }
        JsonNode newPlan = activePlan(newPlanId);
        JsonNode oldPlan = json.readTree(store.plan(oldPlanId).orElseThrow());
        if (!Rows.text(newPlan, "currency").equals(Rows.text(sub, "currency"))) {
            throw ApiException.unprocessable("currency_mismatch", "The new plan must be billed in " + Rows.text(sub, "currency"));
        }
        LocalDate planFrom = Rows.date(newPlan, "valid_from");
        LocalDate planTo = Rows.date(newPlan, "valid_to");
        if (effective.isBefore(planFrom) || (planTo != null && effective.isAfter(planTo))) {
            throw ApiException.unprocessable("plan_not_available", "The new plan is not available on " + effective);
        }
        LocalDate start = Rows.date(sub, "start_date");
        LocalDate end = Rows.date(sub, "end_date");
        if (effective.isBefore(start) || (end != null && effective.isAfter(end))) {
            throw ApiException.unprocessable("invalid_effective_date",
                    "effective_date must be within the subscription term starting " + start);
        }
        ArrayNode adjustments = adjustments(sub);
        for (JsonNode a : adjustments) {
            if (!effective.isAfter(LocalDate.parse(a.get("effective_date").asString()))) {
                throw ApiException.unprocessable("invalid_effective_date",
                        "effective_date must be after the previous plan change on " + a.get("effective_date").asString());
            }
        }
        if (store.invoicedOnOrAfter(id, effective)) {
            throw ApiException.conflict("period_already_invoiced",
                    "An invoice already covers " + effective + "; void it or choose a later effective date");
        }
        BillingPeriods.Period period = BillingPeriods.containing(start, Rows.text(oldPlan, "billing_period"), effective);
        BigDecimal oldFee = Rows.decimalOr(oldPlan, "platform_fee", BigDecimal.ZERO);
        BigDecimal newFee = Rows.decimalOr(newPlan, "platform_fee", BigDecimal.ZERO);
        BigDecimal amount = BillingPeriods.proration(oldFee, newFee, period, effective);

        ObjectNode adj = json.createObjectNode();
        adj.put("effective_date", effective.toString());
        adj.put("period_start", period.start().toString());
        adj.put("period_end", period.end().toString());
        adj.put("from_plan_id", oldPlanId.toString());
        adj.put("to_plan_id", newPlanId.toString());
        adj.put("amount", amount.toPlainString());
        adj.put("description", (amount.signum() >= 0 ? "Proration charge: " : "Proration credit: ")
                + Rows.text(oldPlan, "code") + " -> " + Rows.text(newPlan, "code") + " from " + effective);
        adjustments.add(adj);

        ObjectNode changes = json.createObjectNode();
        changes.put("price_plan_id", newPlanId.toString());
        changes.set("proration_adjustments", adjustments);
        String row = crud.updateRow("tenant_subscription", Map.of("id", id), changes, null).orElseThrow();
        publish(EVENT_PLAN_CHANGED, row);
        return row;
    }

    /** Cancels at the end of the current billing period: status CANCELLED, end_date = period end. */
    @Transactional
    public String cancel(UUID id, String reason, LocalDate today) {
        JsonNode sub = json.readTree(store.lockSubscription(id));
        String from = Rows.text(sub, "status");
        try {
            BillingStates.require(BillingStates.SUBSCRIPTION, "Subscription", from, "CANCELLED");
        } catch (BillingStates.TransitionException e) {
            throw ApiException.conflict("invalid_transition", e.getMessage());
        }
        JsonNode plan = json.readTree(store.plan(Rows.uuid(sub, "price_plan_id")).orElseThrow());
        LocalDate start = Rows.date(sub, "start_date");
        LocalDate ref = today.isBefore(start) ? start : today;
        LocalDate periodEnd = BillingPeriods.containing(start, Rows.text(plan, "billing_period"), ref).end();
        LocalDate currentEnd = Rows.date(sub, "end_date");
        ObjectNode changes = json.createObjectNode();
        changes.put("status", "CANCELLED");
        changes.put("end_date", (currentEnd != null && currentEnd.isBefore(periodEnd) ? currentEnd : periodEnd).toString());
        changes.put("cancellation_reason", reason);
        changes.put("cancelled_at", io.meridian.common.time.Clocks.now().toString());
        String row = crud.updateRow("tenant_subscription", Map.of("id", id), changes, null).orElseThrow();
        publish(EVENT_CANCELLED, row);
        return row;
    }

    /** Payment state follows invoices: overdue -> PAST_DUE, settled -> ACTIVE again. */
    void markPastDue(UUID id) {
        JsonNode sub = json.readTree(store.lockSubscription(id));
        if ("ACTIVE".equals(Rows.text(sub, "status"))) {
            ObjectNode changes = json.createObjectNode().put("status", "PAST_DUE");
            publish(EVENT_CHANGED, crud.updateRow("tenant_subscription", Map.of("id", id), changes, null).orElseThrow());
        }
    }

    void reactivateIfSettled(UUID id) {
        JsonNode sub = json.readTree(store.lockSubscription(id));
        if ("PAST_DUE".equals(Rows.text(sub, "status")) && store.overdueInvoices(id) == 0) {
            ObjectNode changes = json.createObjectNode().put("status", "ACTIVE");
            publish(EVENT_CHANGED, crud.updateRow("tenant_subscription", Map.of("id", id), changes, null).orElseThrow());
        }
    }

    private ArrayNode adjustments(JsonNode sub) {
        JsonNode a = sub.get("proration_adjustments");
        return a instanceof ArrayNode arr ? arr.deepCopy() : json.createArrayNode();
    }

    private JsonNode activePlan(UUID planId) {
        JsonNode plan = json.readTree(store.plan(planId)
                .orElseThrow(() -> ApiException.unprocessable("plan_not_found", "Price plan " + planId + " does not exist")));
        if (!"ACTIVE".equals(Rows.text(plan, "status"))) {
            throw ApiException.unprocessable("plan_not_active", "Price plan " + Rows.text(plan, "code") + " is not ACTIVE");
        }
        return plan;
    }

    private static void validateTerms(JsonNode v) {
        BigDecimal discount = Rows.decimal(v, "discount_rate");
        if (discount != null && (discount.signum() < 0 || discount.compareTo(BigDecimal.ONE) >= 0)) {
            throw ApiException.unprocessable("invalid_discount_rate", "discount_rate must be a fraction in [0, 1)");
        }
        BigDecimal commitment = Rows.decimal(v, "committed_monthly_spend");
        if (commitment != null && commitment.signum() < 0) {
            throw ApiException.unprocessable("invalid_commitment", "committed_monthly_spend must not be negative");
        }
        long terms = Rows.longOr(v, "payment_terms_days", 30);
        if (terms < 0 || terms > 365) {
            throw ApiException.unprocessable("invalid_payment_terms", "payment_terms_days must be between 0 and 365");
        }
        LocalDate start = Rows.date(v, "start_date");
        LocalDate end = Rows.date(v, "end_date");
        if (start != null && end != null && end.isBefore(start)) {
            throw ApiException.unprocessable("invalid_term", "end_date must not be before start_date");
        }
    }

    private void validateCustomPrices(JsonNode custom) {
        if (!custom.isObject()) {
            throw ApiException.unprocessable("invalid_custom_prices", "custom_prices must be an object");
        }
        for (String k : custom.propertyNames()) {
            if (!CUSTOM_PRICE_KEYS.contains(k)) {
                throw ApiException.unprocessable("invalid_custom_prices",
                        "custom_prices supports " + CUSTOM_PRICE_KEYS + "; '" + k + "' is not allowed");
            }
        }
        for (String f : new String[] {"platform_fee", "price_per_active_account"}) {
            if (custom.hasNonNull(f)) {
                try {
                    if (new BigDecimal(custom.get(f).asString()).signum() < 0) {
                        throw ApiException.unprocessable("invalid_custom_prices", f + " must not be negative");
                    }
                } catch (NumberFormatException e) {
                    throw ApiException.unprocessable("invalid_custom_prices", f + " must be a decimal");
                }
            }
        }
        PricePlanService.validateMetricPrices(custom.get("metric_prices"), json);
    }

    private void publish(String type, String row) {
        JsonNode payload = json.readTree(row);
        outbox.publish(type, "tenant_subscription", UUID.fromString(payload.get("id").asString()), payload);
    }
}
