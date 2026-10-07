package io.meridian.insights.application;

import static io.meridian.insights.application.Json.text;

import io.meridian.common.error.ApiException;
import io.meridian.insights.api.generated.model.CashFlowForecast;
import io.meridian.insights.domain.CashFlowForecaster;
import io.meridian.insights.domain.InsightRules;
import io.meridian.insights.infrastructure.ExternalServices;
import io.meridian.insights.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Cash-flow forecasts: the account's available balance (deposit-service) rolled forward with the customer's known
 * recurring series and the trailing 90-day average of non-recurring net flows. Each forecast is an append-only
 * snapshot; a projected balance below the threshold raises a low-balance event and insight.
 */
@Service
public class ForecastService {
    static final String T = "cash_flow_forecast";
    static final int TRAILING_DAYS = 90;

    private final Rows rows;
    private final Events events;
    private final ExternalServices external;
    private final InsightCards cards;
    private final BigDecimal defaultThreshold;

    public ForecastService(Rows rows, Events events, ExternalServices external, InsightCards cards,
                           @Value("${meridian.insights.low-balance-threshold:0}") BigDecimal defaultThreshold) {
        this.rows = rows;
        this.events = events;
        this.external = external;
        this.cards = cards;
        this.defaultThreshold = defaultThreshold;
    }

    @Transactional
    public ObjectNode create(JsonNode req) {
        UUID customer = Json.uuid(req, "customer_id");
        UUID account = Json.uuid(req, "deposit_account_id");
        if (customer == null || account == null) {
            throw ApiException.badRequest("validation_failed", "customer_id and deposit_account_id are required");
        }
        int horizon = Json.integer(req, "horizon_days", 30);
        return compute(customer, account, horizon, Json.decimal(req, "low_balance_threshold"), true);
    }

    /**
     * Computes and stores a forecast. With {@code raiseInsight} a low projected balance also raises the
     * LOW_BALANCE_FORECAST card here (on-demand generation raises it itself).
     */
    @Transactional
    public ObjectNode compute(UUID customer, UUID account, int horizon, BigDecimal threshold, boolean raiseInsight) {
        if (horizon < 7 || horizon > 90) {
            throw new ApiException(422, "invalid_horizon", "horizon_days must be between 7 and 90",
                    List.of(new ApiException.FieldError("horizon_days", "out_of_range", "7..90")));
        }
        JsonNode acc = external.depositAccount(account);
        String owner = acc == null ? null : text(acc, "customer_id");
        if (owner != null && !owner.equals(customer.toString())) {
            throw ApiException.unprocessable("account_not_owned", "Deposit account " + account + " does not belong to the customer");
        }
        JsonNode bal = external.balance(account);
        BigDecimal balance = Json.decimal(bal, "available_balance");
        if (balance == null) {
            balance = Json.decimal(bal, "posted_balance");
        }
        String currency = text(bal, "currency");
        if (currency == null && acc != null) {
            currency = text(acc, "currency");
        }
        if (balance == null || currency == null) {
            throw new ApiException(502, "upstream_error", "deposit-service returned no balance for " + account);
        }
        OffsetDateTime now = rows.now();
        LocalDate asOf = rows.today();
        List<CashFlowForecaster.Series> series = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (ObjectNode rp : rows.query("""
                select row_to_json(t)::text from recurring_payment t where t.customer_id = ? and t.currency = ?
                  and t.status in ('DETECTED', 'CONFIRMED') and t.next_expected_at is not null
                order by t.merchant_clean_name""", customer, currency)) {
            BigDecimal amount = Json.decimal(rp, "last_amount") != null ? Json.decimal(rp, "last_amount")
                    : Json.decimal(rp, "typical_amount");
            boolean income = rp.path("is_income").asBoolean(false);
            series.add(new CashFlowForecaster.Series(text(rp, "merchant_clean_name"), text(rp, "frequency"),
                    income ? amount.abs() : amount.abs().negate(), Json.ts(rp, "next_expected_at").toLocalDate()));
            names.add(text(rp, "merchant_clean_name").toLowerCase());
        }
        BigDecimal net = rows.jdbc().sql("""
                select coalesce(sum(t.amount), 0) from transaction_enrichment t
                where t.customer_id = ? and t.currency = ? and t.transaction_at > now() - make_interval(days => ?)
                  and t.transaction_at <= now() and t.recurring_payment_id is null
                  and not (lower(coalesce(t.merchant_clean_name, '')) = any (?::text[]))
                  and not exists (select 1 from transaction_enrichment s where s.supersedes_id = t.id)""")
                .params(customer, currency, TRAILING_DAYS, names.toArray(new String[0])).query(BigDecimal.class).single();
        CashFlowForecaster.Forecast f = CashFlowForecaster.project(asOf, horizon, balance, series,
                CashFlowForecaster.dailyAverage(net, TRAILING_DAYS));
        ObjectNode v = rows.object();
        v.put("customer_id", customer.toString());
        v.put("deposit_account_id", account.toString());
        v.put("horizon_days", horizon);
        if (threshold != null) {
            v.put("low_balance_threshold", threshold.toPlainString());
        }
        v.put("as_of", now.toString());
        v.put("currency", currency);
        v.put("starting_balance", f.startingBalance().toPlainString());
        v.put("lowest_balance", f.lowestBalance().toPlainString());
        v.put("lowest_balance_date", f.lowestBalanceDate().toString());
        ArrayNode points = v.putArray("projected_points");
        for (CashFlowForecaster.Point p : f.points()) {
            ObjectNode o = points.addObject();
            o.put("date", p.date().toString());
            o.put("balance", p.balance().toPlainString());
            o.put("inflows", p.inflows().toPlainString());
            o.put("outflows", p.outflows().toPlainString());
            ArrayNode items = o.putArray("items");
            p.items().forEach(it -> items.addObject().put("name", it.name()).put("amount", it.amount().toPlainString()));
        }
        v.put("expected_income", f.expectedIncome().toPlainString());
        v.put("expected_bills", f.expectedBills().toPlainString());
        v.put("expected_discretionary", f.expectedDiscretionary().toPlainString());
        v.put("model_version", CashFlowForecaster.MODEL_VERSION);
        ObjectNode row = rows.insert(T, v);
        events.publish("engagement.cash_flow.forecast_created", T, row, CashFlowForecast.class);
        BigDecimal limit = threshold == null ? defaultThreshold : threshold;
        if (f.lowestBalance().compareTo(limit) < 0) {
            events.publish("engagement.cash_flow.low_balance_forecast", T, row, CashFlowForecast.class);
            if (raiseInsight) {
                cards.raise(customer, InsightRules.lowBalance(Json.uuid(row, "id"), account, currency, f.lowestBalance(),
                        f.lowestBalanceDate(), limit), true);
            }
        }
        return row;
    }
}
