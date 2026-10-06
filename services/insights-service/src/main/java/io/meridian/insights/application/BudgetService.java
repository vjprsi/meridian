package io.meridian.insights.application;

import static io.meridian.insights.application.Json.text;

import io.meridian.common.error.ApiException;
import io.meridian.common.error.ApiException.FieldError;
import io.meridian.insights.api.generated.model.BudgetPeriod;
import io.meridian.insights.domain.BudgetPeriods;
import io.meridian.insights.domain.InsightRules;
import io.meridian.insights.domain.Lifecycles;
import io.meridian.insights.domain.RecurringDetector;
import io.meridian.insights.infrastructure.Rows;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Budgets and their periods. Period actuals are always recomputed from the customer's current (not superseded)
 * spend enrichments in the budget currency, for the budget category and all its subcategories (or all spending), so
 * re-categorisations and late transactions are reflected exactly. Alerts fire once per period: at the threshold share
 * (BUDGET_NEAR_LIMIT) and when exceeded (BUDGET_EXCEEDED), delivered through notification-service when the budget
 * notifies the customer. Paused and archived budgets are neither tracked nor alerted.
 */
@Service
public class BudgetService {
    static final String T = "budget";
    static final String P = "budget_period";

    private final Rows rows;
    private final CrudEngine crud;
    private final Events events;
    private final InsightCards cards;

    public BudgetService(Rows rows, CrudEngine crud, Events events, InsightCards cards) {
        this.rows = rows;
        this.crud = crud;
        this.events = events;
        this.cards = cards;
    }

    @Transactional
    public ObjectNode create(Object body) {
        ObjectNode v = crud.toColumns(T, body);
        validate(v);
        v.put("status", "ACTIVE");
        ObjectNode b = rows.insert(T, v);
        LocalDate today = rows.today();
        if (!LocalDate.parse(text(b, "starts_on")).isAfter(today)) {
            ensurePeriod(b, today, true);
        }
        return b;
    }

    @Transactional
    public ObjectNode update(UUID id, Object body, int expectedVersion) {
        ObjectNode current = rows.lock(T, id);
        ObjectNode changes = crud.toColumns(T, body);
        boolean hasPeriods = rows.scalar("select 1 from budget_period where budget_id = ? limit 1", Integer.class, id).isPresent();
        if (hasPeriods) {
            for (String f : List.of("period_type", "starts_on", "currency", "customer_id")) {
                if (changes.has(f) && !changes.get(f).asString().equals(text(current, f))) {
                    throw ApiException.unprocessable("immutable_field", f + " cannot change once the budget has periods");
                }
            }
        }
        ObjectNode merged = current.deepCopy();
        merged.setAll(changes);
        validate(merged);
        ObjectNode b = crud.updateRow(T, Map.of("id", id), changes, expectedVersion).map(rows::tree)
                .orElseThrow(() -> Json.versionConflict(expectedVersion));
        if ("ACTIVE".equals(text(b, "status")) && (changes.has("amount") || changes.has("spend_category_id")
                || changes.has("alert_threshold_pct"))) {
            LocalDate today = rows.today();
            if (!LocalDate.parse(text(b, "starts_on")).isAfter(today)) {
                ObjectNode p = ensurePeriod(b, today, false);
                ObjectNode amount = rows.object();
                amount.put("budget_amount", BudgetPeriods.withRollover(Json.decimal(b, "amount"), b.path("rollover").asBoolean(),
                        Json.decimal(p, "rollover_amount")).toPlainString());
                recompute(b, rows.update(P, Json.uuid(p, "id"), amount));
            }
        }
        return b;
    }

    private void validate(ObjectNode v) {
        List<FieldError> errors = new ArrayList<>();
        BigDecimal amount = Json.decimal(v, "amount");
        if (amount == null || amount.signum() <= 0) {
            errors.add(new FieldError("amount", "must_be_positive", "amount must be > 0"));
        }
        BigDecimal pct = Json.decimal(v, "alert_threshold_pct");
        if (pct != null && (pct.signum() <= 0 || pct.compareTo(BigDecimal.ONE) > 0)) {
            errors.add(new FieldError("alert_threshold_pct", "out_of_range", "alert_threshold_pct must be in (0, 1]"));
        }
        UUID category = Json.uuid(v, "spend_category_id");
        if (category != null && rows.scalar("select 1 from spend_category where id = ? and is_active", Integer.class,
                category).isEmpty()) {
            errors.add(new FieldError("spend_category_id", "not_found", "Active category " + category + " does not exist"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(422, "invalid_budget", "Budget is invalid", errors);
        }
    }

    @Transactional
    public ObjectNode transition(UUID id, String action) {
        ObjectNode b = rows.lock(T, id);
        ObjectNode v = rows.object();
        v.put("status", Lifecycles.BUDGET.apply(action, text(b, "status")));
        ObjectNode row = rows.update(T, id, v);
        if ("resume".equals(action)) {
            LocalDate today = rows.today();
            if (!LocalDate.parse(text(row, "starts_on")).isAfter(today)) {
                ensurePeriod(row, today, true);
            }
        }
        return row;
    }

    /** The period containing today, opened and recomputed (alerts included for active budgets). */
    @Transactional
    public ObjectNode currentPeriod(UUID id) {
        ObjectNode b = rows.lock(T, id);
        LocalDate today = rows.today();
        if (LocalDate.parse(text(b, "starts_on")).isAfter(today)) {
            throw ApiException.unprocessable("budget_not_started", "The budget starts on " + text(b, "starts_on"));
        }
        return ensurePeriod(b, today, "ACTIVE".equals(text(b, "status")));
    }

    /** Recomputes the active budgets of a customer and currency whose period covers {@code date}. */
    public void onSpend(UUID customerId, String currency, LocalDate date) {
        for (ObjectNode b : rows.query("""
                select row_to_json(t)::text from budget t where t.customer_id = ? and t.currency = ? and t.status = 'ACTIVE'
                  and t.starts_on <= ? order by t.id for update""", customerId, currency, date)) {
            ensurePeriod(b, date, true);
        }
    }

    /** Recomputes every existing period of the customer's active budgets (after bulk re-categorisation). */
    public void recomputeCustomer(UUID customerId) {
        for (ObjectNode b : rows.query("""
                select row_to_json(t)::text from budget t where t.customer_id = ? and t.status = 'ACTIVE' order by t.id for update""",
                customerId)) {
            for (ObjectNode p : rows.query("select row_to_json(t)::text from budget_period t where t.budget_id = ? order by t.period_start",
                    Json.uuid(b, "id"))) {
                recompute(b, p);
            }
        }
    }

    /** Opens (if needed) the period of the budget containing {@code date} and recomputes its actuals. */
    ObjectNode ensurePeriod(ObjectNode budget, LocalDate date, boolean recompute) {
        UUID budgetId = Json.uuid(budget, "id");
        LocalDate startsOn = LocalDate.parse(text(budget, "starts_on"));
        String type = text(budget, "period_type");
        BudgetPeriods.Period period = BudgetPeriods.containing(startsOn, type, date).orElseThrow(() ->
                ApiException.unprocessable("budget_not_started", "The budget starts on " + startsOn));
        Optional<ObjectNode> existing = rows.queryOne(
                "select row_to_json(t)::text from budget_period t where t.budget_id = ? and t.period_start = ?", budgetId, period.start());
        ObjectNode p;
        if (existing.isPresent()) {
            p = existing.get();
        } else {
            BigDecimal carried = BigDecimal.ZERO;
            if (budget.path("rollover").asBoolean(false) && period.start().isAfter(startsOn)) {
                Optional<ObjectNode> previous = rows.queryOne(
                        "select row_to_json(t)::text from budget_period t where t.budget_id = ? and t.period_end = ?",
                        budgetId, period.start().minusDays(1));
                if (previous.isPresent()) {
                    ObjectNode prev = recompute(budget, previous.get());
                    BigDecimal remaining = Json.decimal(prev, "remaining_amount");
                    carried = remaining == null || remaining.signum() < 0 ? BigDecimal.ZERO : remaining;
                }
            }
            ObjectNode v = rows.object();
            v.put("budget_id", budgetId.toString());
            v.put("period_start", period.start().toString());
            v.put("period_end", period.end().toString());
            BigDecimal amount = BudgetPeriods.withRollover(Json.decimal(budget, "amount"), true, carried);
            v.put("budget_amount", amount.toPlainString());
            v.put("rollover_amount", carried.toPlainString());
            v.put("spent_amount", "0");
            v.put("remaining_amount", amount.toPlainString());
            p = rows.insert(P, v);
        }
        return recompute ? recompute(budget, p) : p;
    }

    /** Recomputes the actuals of one period from the current enrichments and raises once-per-period alerts. */
    ObjectNode recompute(ObjectNode budget, ObjectNode period) {
        UUID category = Json.uuid(budget, "spend_category_id");
        LocalDate start = LocalDate.parse(text(period, "period_start"));
        LocalDate end = LocalDate.parse(text(period, "period_end"));
        String sql = """
                select coalesce(sum(-t.amount), 0) as spent, count(*) as n from transaction_enrichment t
                where t.customer_id = ? and t.currency = ? and t.amount < 0
                  and t.transaction_at >= ? and t.transaction_at < ?
                  and not exists (select 1 from transaction_enrichment s where s.supersedes_id = t.id)""";
        List<Object> args = new ArrayList<>(List.of(Json.uuid(budget, "customer_id"), text(budget, "currency"),
                start.atStartOfDay().atOffset(ZoneOffset.UTC), end.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC)));
        if (category != null) {
            sql += """
                     and t.spend_category_id in (with recursive c(id) as (select ?::uuid union all
                         select s.id from spend_category s join c on s.parent_id = c.id) select id from c)""";
            args.add(category);
        }
        BigDecimal[] spent = new BigDecimal[1];
        int[] count = new int[1];
        rows.jdbc().sql(sql).params(args).query((rs, i) -> {
            spent[0] = rs.getBigDecimal(1);
            count[0] = rs.getInt(2);
            return 1;
        }).list();
        BigDecimal budgetAmount = Json.decimal(period, "budget_amount");
        ObjectNode v = rows.object();
        v.put("spent_amount", spent[0].toPlainString());
        v.put("remaining_amount", budgetAmount.subtract(spent[0]).toPlainString());
        v.put("transaction_count", count[0]);
        UUID periodId = Json.uuid(period, "id");
        ObjectNode p = rows.update(P, periodId, v);
        if (!"ACTIVE".equals(text(budget, "status"))) {
            return p;
        }
        BigDecimal pct = Json.decimal(budget, "alert_threshold_pct");
        BudgetPeriods.Alert level = BudgetPeriods.level(spent[0], budgetAmount, pct == null ? new BigDecimal("0.8") : pct);
        boolean notify = budget.path("notify_customer").asBoolean(true);
        UUID customer = Json.uuid(budget, "customer_id");
        if (level != BudgetPeriods.Alert.NONE && text(p, "alert_sent_at") == null) {
            ObjectNode a = rows.object();
            a.put("alert_sent_at", rows.now().toString());
            p = rows.update(P, periodId, a);
            events.publish("engagement.budget.threshold_reached", P, p, BudgetPeriod.class);
            if (level == BudgetPeriods.Alert.THRESHOLD) {
                cards.raise(customer, InsightRules.budget(false, periodId, Json.uuid(budget, "id"), text(budget, "name"),
                        text(budget, "currency"), spent[0], budgetAmount, start), notify);
            }
        }
        if (level == BudgetPeriods.Alert.EXCEEDED && text(p, "exceeded_at") == null) {
            ObjectNode a = rows.object();
            a.put("exceeded_at", rows.now().toString());
            p = rows.update(P, periodId, a);
            events.publish("engagement.budget.exceeded", P, p, BudgetPeriod.class);
            cards.raise(customer, InsightRules.budget(true, periodId, Json.uuid(budget, "id"), text(budget, "name"),
                    text(budget, "currency"), spent[0], budgetAmount, start), notify);
        }
        return p;
    }

    public JsonNode periods(UUID id, Integer limit, String cursor) {
        rows.get(T, id);
        return rows.page("select row_to_json(t)::text from budget_period t where t.budget_id = ?", List.of(id), limit, cursor);
    }

    /** Suggested monthly budgets: median monthly spend per top category over the last full months, rounded up to 10. */
    @Transactional(readOnly = true)
    public JsonNode suggest(UUID customerId, int months) {
        if (months < 1 || months > 12) {
            throw new ApiException(422, "invalid_months", "months must be between 1 and 12",
                    List.of(new FieldError("months", "out_of_range", "1..12")));
        }
        LocalDate firstOfMonth = rows.today().withDayOfMonth(1);
        LocalDate from = firstOfMonth.minusMonths(months);
        Map<String, Map<String, BigDecimal>> byCategory = new LinkedHashMap<>();
        Map<String, String[]> meta = new LinkedHashMap<>();
        rows.jdbc().sql("""
                select t.spend_category_id, c.code, c.name, t.currency, to_char(t.transaction_at at time zone 'UTC', 'YYYY-MM') m,
                       sum(-t.amount) spend
                from transaction_enrichment t join spend_category c on c.id = t.spend_category_id
                where t.customer_id = ? and t.amount < 0 and not c.is_income
                  and t.transaction_at >= ? and t.transaction_at < ?
                  and not exists (select 1 from transaction_enrichment s where s.supersedes_id = t.id)
                group by t.spend_category_id, c.code, c.name, t.currency, m""")
                .params(customerId, from.atStartOfDay().atOffset(ZoneOffset.UTC), firstOfMonth.atStartOfDay().atOffset(ZoneOffset.UTC))
                .query((rs, i) -> {
                    String key = rs.getString(1) + "|" + rs.getString(4);
                    byCategory.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(rs.getString(5), rs.getBigDecimal(6));
                    meta.put(key, new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)});
                    return 1;
                }).list();
        record S(String[] meta, BigDecimal median, Map<String, BigDecimal> monthly, BigDecimal total) {}
        List<S> all = new ArrayList<>();
        byCategory.forEach((key, monthly) -> {
            List<BigDecimal> values = new ArrayList<>();
            for (int i = 0; i < months; i++) {
                String m = from.plusMonths(i).toString().substring(0, 7);
                values.add(monthly.getOrDefault(m, BigDecimal.ZERO));
            }
            all.add(new S(meta.get(key), RecurringDetector.median(values), monthly,
                    values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)));
        });
        all.sort((a, b) -> b.total().compareTo(a.total()));
        ObjectNode out = rows.object();
        out.put("customer_id", customerId.toString());
        out.put("months", months);
        out.put("from", from.toString());
        out.put("to", firstOfMonth.minusDays(1).toString());
        ArrayNode list = out.putArray("suggestions");
        for (S s : all.stream().filter(s -> s.median().signum() > 0).limit(8).toList()) {
            ObjectNode o = list.addObject();
            o.put("spend_category_id", s.meta()[0]);
            o.put("category_code", s.meta()[1]);
            o.put("category_name", s.meta()[2]);
            o.put("currency", s.meta()[3]);
            o.put("median_monthly_spend", s.median().setScale(2, RoundingMode.HALF_EVEN).toPlainString());
            BigDecimal suggested = s.median().divide(BigDecimal.TEN, 0, RoundingMode.CEILING).multiply(BigDecimal.TEN);
            o.put("suggested_amount", suggested.setScale(2, RoundingMode.HALF_EVEN).toPlainString());
            o.put("period_type", "MONTHLY");
            ObjectNode m = o.putObject("monthly_spend");
            s.monthly().forEach((k, val) -> m.put(k, val.setScale(2, RoundingMode.HALF_EVEN).toPlainString()));
        }
        return out;
    }

    /** End of day: opens the next period of every active budget whose current period ends on the business date. */
    @Transactional
    public int rollover(LocalDate businessDate) {
        int opened = 0;
        for (ObjectNode b : rows.query("""
                select row_to_json(t)::text from budget t where t.status = 'ACTIVE' and t.starts_on <= ? order by t.id for update""",
                businessDate)) {
            BudgetPeriods.Period p = BudgetPeriods.containing(LocalDate.parse(text(b, "starts_on")), text(b, "period_type"),
                    businessDate).orElseThrow();
            if (p.end().equals(businessDate)) {
                ensurePeriod(b, businessDate, true);
                boolean exists = rows.scalar("select 1 from budget_period where budget_id = ? and period_start = ?", Integer.class,
                        Json.uuid(b, "id"), businessDate.plusDays(1)).isPresent();
                if (!exists) {
                    ensurePeriod(b, businessDate.plusDays(1), false);
                    opened++;
                }
            }
        }
        return opened;
    }
}
