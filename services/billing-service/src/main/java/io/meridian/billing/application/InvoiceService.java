package io.meridian.billing.application;

import io.meridian.billing.domain.BillingPeriods;
import io.meridian.billing.domain.BillingStates;
import io.meridian.billing.domain.InvoiceCalculator;
import io.meridian.billing.domain.TieredPrice;
import io.meridian.billing.domain.UsageMetrics;
import io.meridian.billing.infrastructure.BillingStore;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Invoicing: draft generation from usage x plan, issue, void, payments and the overdue sweep. */
@Service
public class InvoiceService {
    private static final DateTimeFormatter YYYYMM = DateTimeFormatter.ofPattern("yyyyMM");
    private static final int MAX_PERIOD_DAYS = 366;

    private final CrudEngine crud;
    private final BillingStore store;
    private final OutboxWriter outbox;
    private final SubscriptionService subscriptions;
    private final JsonMapper json;
    private final BigDecimal taxRate;

    public InvoiceService(CrudEngine crud, BillingStore store, OutboxWriter outbox, SubscriptionService subscriptions,
                          JsonMapper json, @Value("${meridian.billing.tax-rate:0}") BigDecimal taxRate) {
        this.crud = crud;
        this.store = store;
        this.outbox = outbox;
        this.subscriptions = subscriptions;
        this.json = json;
        if (taxRate.signum() < 0 || taxRate.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("meridian.billing.tax-rate must be a fraction in [0, 1)");
        }
        this.taxRate = taxRate;
    }

    /**
     * Generates one DRAFT invoice per billable subscription for [from, to]. Subscriptions that already have a
     * non-void invoice overlapping the period are skipped; if nothing could be generated the call fails with 409.
     */
    @Transactional
    public ObjectNode generate(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw ApiException.unprocessable("invalid_period", "period_end must not be before period_start");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_PERIOD_DAYS) {
            throw ApiException.unprocessable("period_too_long", "An invoice period covers at most " + MAX_PERIOD_DAYS + " days");
        }
        store.lockTenant("invoice", TenantContext.require());
        List<String> subs = store.billableSubscriptions(from, to);
        if (subs.isEmpty()) {
            throw ApiException.unprocessable("no_billable_subscription",
                    "No active (non-trial) subscription covers " + from + " to " + to);
        }
        ObjectNode out = json.createObjectNode();
        ArrayNode data = out.putArray("data");
        ArrayNode skipped = out.putArray("skipped_subscription_ids");
        for (String s : subs) {
            JsonNode sub = json.readTree(s);
            UUID subId = Rows.uuid(sub, "id");
            if (store.invoicedOverlapping(subId, from, to)) {
                skipped.add(subId.toString());
                continue;
            }
            data.add(json.readTree(createInvoice(sub, from, to)));
        }
        if (data.isEmpty()) {
            throw ApiException.conflict("invoice_exists",
                    "Every billable subscription already has an invoice overlapping " + from + " to " + to);
        }
        return out;
    }

    private String createInvoice(JsonNode sub, LocalDate from, LocalDate to) {
        BillingPeriods.Period period = new BillingPeriods.Period(from, to);
        List<JsonNode> adjustments = new ArrayList<>();
        sub.path("custom_prices").path("proration_adjustments").forEach(adjustments::add);
        JsonNode planAtStart = planAt(sub, adjustments, from);
        JsonNode planAtEnd = planAt(sub, adjustments, to);
        JsonNode custom = sub.path("custom_prices");

        Map<String, Long> usage = new HashMap<>();
        long peakAccounts = 0;
        for (BillingStore.MetricTotal t : store.usageTotals(from, to, null)) {
            usage.put(t.metric(), UsageMetrics.isGauge(t.metric()) ? t.peakDaily() : t.total());
            if ("ACTIVE_ACCOUNT".equals(t.metric())) {
                peakAccounts = t.peakDaily();
            }
        }
        Map<String, TieredPrice> prices = new LinkedHashMap<>(
                UsageMetrics.parsePrices(json.convertValue(planAtEnd.get("metric_prices"), Map.class)));
        if (custom.get("metric_prices") instanceof ObjectNode overrides) {
            prices.putAll(UsageMetrics.parsePrices(json.convertValue(overrides, Map.class)));
        }
        List<InvoiceCalculator.Adjustment> adj = new ArrayList<>();
        for (JsonNode a : adjustments) {
            if (period.contains(LocalDate.parse(a.get("effective_date").asString()))) {
                adj.add(new InvoiceCalculator.Adjustment(a.get("description").asString(), new BigDecimal(a.get("amount").asString())));
            }
        }
        BigDecimal coverage = BillingPeriods.coverage(period, Rows.date(sub, "start_date"), Rows.date(sub, "end_date"));
        InvoiceCalculator.Result r = InvoiceCalculator.calculate(new InvoiceCalculator.Input(
                Rows.text(planAtStart, "code"),
                BillingPeriods.months(Rows.text(planAtEnd, "billing_period")),
                custom.hasNonNull("platform_fee") ? new BigDecimal(custom.get("platform_fee").asString())
                        : Rows.decimalOr(planAtStart, "platform_fee", BigDecimal.ZERO),
                coverage,
                Rows.longOr(planAtEnd, "included_active_accounts", 0),
                custom.hasNonNull("price_per_active_account") ? new BigDecimal(custom.get("price_per_active_account").asString())
                        : Rows.decimalOr(planAtEnd, "price_per_active_account", BigDecimal.ZERO),
                peakAccounts,
                usage,
                prices,
                Rows.decimal(sub, "discount_rate"),
                adj,
                Rows.decimal(sub, "committed_monthly_spend"),
                taxRate));

        String prefix = "INV-" + from.format(YYYYMM) + "-";
        String number = prefix + String.format("%06d", store.countInvoiceNumbers(prefix) + 1);
        ObjectNode inv = json.createObjectNode();
        inv.put("tenant_subscription_id", Rows.text(sub, "id"));
        inv.put("invoice_number", number);
        inv.put("period_start", from.toString());
        inv.put("period_end", to.toString());
        inv.put("currency", Rows.text(sub, "currency"));
        inv.put("subtotal", r.subtotal().toPlainString());
        inv.put("tax_amount", r.taxAmount().toPlainString());
        inv.put("total", r.total().toPlainString());
        inv.put("amount_paid", "0");
        inv.put("status", "DRAFT");
        String row = crud.insert("tenant_invoice", inv);
        String invoiceId = json.readTree(row).get("id").asString();
        int lineNo = 1;
        for (InvoiceCalculator.Line l : r.lines()) {
            ObjectNode line = json.createObjectNode();
            line.put("tenant_invoice_id", invoiceId);
            line.put("line_no", lineNo++);
            if (l.metric() != null) {
                line.put("metric", l.metric());
            }
            line.put("description", truncate(l.description(), 255));
            line.put("quantity", l.quantity());
            line.put("unit_price", l.unitPrice().toPlainString());
            line.put("amount", l.amount().toPlainString());
            if (l.tier() != null) {
                line.put("tier", truncate(l.tier(), 64));
            }
            crud.insert("tenant_invoice_line", line);
        }
        return row;
    }

    /** The plan in force on {@code date}: the from-plan of the first plan change after that date, else the current plan. */
    private JsonNode planAt(JsonNode sub, List<JsonNode> adjustments, LocalDate date) {
        UUID planId = Rows.uuid(sub, "price_plan_id");
        LocalDate best = null;
        for (JsonNode a : adjustments) {
            LocalDate eff = LocalDate.parse(a.get("effective_date").asString());
            if (eff.isAfter(date) && (best == null || eff.isBefore(best))) {
                best = eff;
                planId = UUID.fromString(a.get("from_plan_id").asString());
            }
        }
        UUID id = planId;
        return json.readTree(store.plan(id).orElseThrow(() -> new IllegalStateException("plan " + id + " missing")));
    }

    @Transactional
    public String issue(UUID id, LocalDate today) {
        JsonNode inv = json.readTree(store.lockInvoice(id));
        transition(inv, "ISSUED");
        JsonNode sub = json.readTree(store.subscription(Rows.uuid(inv, "tenant_subscription_id")));
        ObjectNode changes = json.createObjectNode();
        boolean nothingDue = Rows.decimal(inv, "total").signum() <= 0;
        changes.put("status", nothingDue ? "PAID" : "ISSUED");
        changes.put("issued_at", OffsetDateTime.now().toString());
        changes.put("due_date", today.plusDays(Rows.longOr(sub, "payment_terms_days", 30)).toString());
        if (nothingDue) {
            changes.put("paid_at", OffsetDateTime.now().toString());
        }
        String row = crud.updateRow("tenant_invoice", Map.of("id", id), changes, null).orElseThrow();
        outbox.publish("platform.tenant_invoice.issued", "tenant_invoice", id, json.readTree(row));
        if (nothingDue) {
            outbox.publish("platform.tenant_invoice.paid", "tenant_invoice", id, json.readTree(row));
        }
        return row;
    }

    @Transactional
    public String voidInvoice(UUID id, String reason) {
        JsonNode inv = json.readTree(store.lockInvoice(id));
        if (Rows.decimalOr(inv, "amount_paid", BigDecimal.ZERO).signum() > 0) {
            throw ApiException.conflict("invoice_has_payments", "An invoice with recorded payments cannot be voided; issue a credit instead");
        }
        transition(inv, "VOID");
        ObjectNode changes = json.createObjectNode().put("status", "VOID");
        String row = crud.updateRow("tenant_invoice", Map.of("id", id), changes, null).orElseThrow();
        UUID subId = Rows.uuid(inv, "tenant_subscription_id");
        subscriptions.reactivateIfSettled(subId);
        return row;
    }

    @Transactional
    public String recordPayment(UUID id, BigDecimal amount, OffsetDateTime paidAt) {
        JsonNode inv = json.readTree(store.lockInvoice(id));
        String status = Rows.text(inv, "status");
        if (!List.of("ISSUED", "PARTIALLY_PAID", "OVERDUE").contains(status)) {
            throw ApiException.conflict("invoice_not_payable", "Payments can only be recorded on issued invoices; invoice is " + status);
        }
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount", "Payment amount must be positive");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw ApiException.unprocessable("invalid_amount", "Payment amount has more than 2 decimals");
        }
        if (paidAt.isAfter(OffsetDateTime.now().plusMinutes(5))) {
            throw ApiException.unprocessable("invalid_paid_at", "paid_at cannot be in the future");
        }
        BigDecimal total = Rows.decimal(inv, "total");
        BigDecimal paid = Rows.decimalOr(inv, "amount_paid", BigDecimal.ZERO);
        BigDecimal outstanding = total.subtract(paid);
        if (amount.compareTo(outstanding) > 0) {
            throw ApiException.unprocessable("overpayment",
                    "Payment " + amount.toPlainString() + " exceeds the outstanding " + outstanding.stripTrailingZeros().toPlainString());
        }
        BigDecimal newPaid = paid.add(amount);
        boolean full = newPaid.compareTo(total) == 0;
        String next = full ? "PAID" : ("OVERDUE".equals(status) ? "OVERDUE" : "PARTIALLY_PAID");
        if (!next.equals(status)) {
            transition(inv, next);
        }
        ObjectNode changes = json.createObjectNode();
        changes.put("amount_paid", newPaid.toPlainString());
        changes.put("status", next);
        if (full) {
            changes.put("paid_at", paidAt.toString());
        }
        String row = crud.updateRow("tenant_invoice", Map.of("id", id), changes, null).orElseThrow();
        if (full) {
            outbox.publish("platform.tenant_invoice.paid", "tenant_invoice", id, json.readTree(row));
            subscriptions.reactivateIfSettled(Rows.uuid(inv, "tenant_subscription_id"));
        }
        return row;
    }

    /** Marks issued invoices past due as OVERDUE and their subscriptions PAST_DUE (current tenant). */
    @Transactional
    public int markOverdue(LocalDate today) {
        List<UUID> subs = store.markOverdue(today);
        subs.forEach(subscriptions::markPastDue);
        return subs.size();
    }

    private static void transition(JsonNode inv, String to) {
        String from = Rows.text(inv, "status");
        try {
            BillingStates.require(BillingStates.INVOICE, "Invoice", from, to);
        } catch (BillingStates.TransitionException e) {
            throw ApiException.conflict("invalid_transition", e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
