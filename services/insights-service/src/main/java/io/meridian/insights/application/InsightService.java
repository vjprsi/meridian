package io.meridian.insights.application;

import static io.meridian.insights.application.Json.text;

import io.meridian.insights.api.generated.model.FinancialInsight;
import io.meridian.insights.domain.InsightRules;
import io.meridian.insights.domain.InsightRules.Candidate;
import io.meridian.insights.domain.Lifecycles;
import io.meridian.insights.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Insight cards: creation with business de-duplication, delivery through notification-service (fail-open: an
 * undelivered card stays PENDING and is still listed in the app), the card lifecycle, on-demand generation and expiry.
 */
@Service
public class InsightService {
    static final String T = "financial_insight";

    private final Rows rows;
    private final InsightCards cards;
    private final ForecastService forecasts;
    private final BigDecimal spikeMinExcess;
    private final BigDecimal lowBalanceThreshold;

    public InsightService(Rows rows, InsightCards cards, ForecastService forecasts,
                          @Value("${meridian.insights.spike-min-excess:50}") BigDecimal spikeMinExcess,
                          @Value("${meridian.insights.low-balance-threshold:0}") BigDecimal lowBalanceThreshold) {
        this.rows = rows;
        this.cards = cards;
        this.forecasts = forecasts;
        this.spikeMinExcess = spikeMinExcess;
        this.lowBalanceThreshold = lowBalanceThreshold;
    }

    @Transactional
    public ObjectNode transition(UUID id, String action) {
        ObjectNode r = rows.lock(T, id);
        String to = Lifecycles.INSIGHT.apply(action, text(r, "delivery_status"));
        OffsetDateTime now = rows.now();
        ObjectNode v = rows.object();
        v.put("delivery_status", to);
        switch (action) {
            case "seen" -> {
                if (text(r, "seen_at") == null) {
                    v.put("seen_at", now.toString());
                }
            }
            case "acted" -> {
                v.put("acted_at", now.toString());
                if (text(r, "seen_at") == null) {
                    v.put("seen_at", now.toString());
                }
            }
            case "dismiss" -> {
                if (text(r, "dismissed_at") == null) {
                    v.put("dismissed_at", now.toString());
                }
            }
            default -> { }
        }
        return rows.update(T, id, v);
    }

    @Transactional
    public ObjectNode feedback(UUID id, boolean useful) {
        ObjectNode r = rows.lock(T, id);
        Lifecycles.INSIGHT.apply("feedback", text(r, "delivery_status"));
        ObjectNode v = rows.object();
        v.put("feedback_useful", useful);
        return rows.update(T, id, v);
    }

    /** Generates the rule-based insights of one customer now; duplicates (same dedupe key) are skipped. */
    @Transactional
    public JsonNode generate(UUID customerId, UUID depositAccountId) {
        List<Candidate> candidates = new ArrayList<>(spendSpikes(customerId));
        LocalDate today = rows.today();
        for (ObjectNode rp : rows.query("""
                select row_to_json(t)::text from recurring_payment t where t.customer_id = ?
                  and t.status in ('DETECTED', 'CONFIRMED')""", customerId)) {
            UUID rid = Json.uuid(rp, "id");
            String merchant = text(rp, "merchant_clean_name");
            OffsetDateTime changedAt = Json.ts(rp, "price_changed_at");
            if (changedAt != null && changedAt.toLocalDate().isAfter(today.minusDays(31)) && text(rp, "previous_amount") != null) {
                candidates.add(InsightRules.priceChange(rid, merchant, text(rp, "currency"),
                        Json.decimal(rp, "previous_amount"), Json.decimal(rp, "last_amount")));
            }
            OffsetDateTime created = Json.ts(rp, "created_at");
            if (created != null && created.toLocalDate().isAfter(today.minusDays(31))) {
                candidates.add(InsightRules.newSeries(rid, merchant, text(rp, "frequency"), text(rp, "currency"),
                        Json.decimal(rp, "typical_amount"), rp.path("is_income").asBoolean(false),
                        Json.ts(rp, "last_seen_at").toLocalDate()));
            }
        }
        ObjectNode out = rows.object();
        if (depositAccountId != null) {
            ObjectNode forecast = forecasts.compute(customerId, depositAccountId, 30, null, false);
            out.put("cash_flow_forecast_id", text(forecast, "id"));
            BigDecimal lowest = Json.decimal(forecast, "lowest_balance");
            BigDecimal threshold = Json.decimal(forecast, "low_balance_threshold");
            if (threshold == null) {
                threshold = lowBalanceThreshold;
            }
            if (lowest.compareTo(threshold) < 0 && text(forecast, "lowest_balance_date") != null) {
                candidates.add(InsightRules.lowBalance(Json.uuid(forecast, "id"), depositAccountId, text(forecast, "currency"),
                        lowest, LocalDate.parse(text(forecast, "lowest_balance_date")), threshold));
            }
        }
        ArrayNode created = rows.json().createArrayNode();
        int duplicates = 0;
        for (Candidate c : candidates) {
            Optional<ObjectNode> row = cards.raise(customerId, c, true);
            if (row.isPresent()) {
                created.add(rows.json().valueToTree(rows.read(row.get(), FinancialInsight.class)));
            } else {
                duplicates++;
            }
        }
        out.put("customer_id", customerId.toString());
        out.put("evaluated", candidates.size());
        out.put("generated", created.size());
        out.put("duplicates_skipped", duplicates);
        out.set("insights", created);
        return out;
    }

    /** Spend spikes per category and currency: last 30 days vs the three previous 30-day windows. */
    List<Candidate> spendSpikes(UUID customerId) {
        Map<String, BigDecimal[]> windows = new LinkedHashMap<>();
        Map<String, String[]> meta = new HashMap<>();
        rows.jdbc().sql("""
                select t.spend_category_id, c.name, t.currency,
                       least(3, floor(extract(epoch from (now() - t.transaction_at)) / 86400 / 30))::int as w,
                       sum(-t.amount) as spend
                from transaction_enrichment t left join spend_category c on c.id = t.spend_category_id
                where t.customer_id = ? and t.amount < 0 and t.transaction_at > now() - interval '120 days'
                  and t.transaction_at <= now()
                  and not exists (select 1 from transaction_enrichment s where s.supersedes_id = t.id)
                  and (c.id is null or not c.is_income)
                group by t.spend_category_id, c.name, t.currency, w""").param(customerId).query((rs, i) -> {
            String key = rs.getString(1) + "|" + rs.getString(3);
            windows.computeIfAbsent(key, k -> new BigDecimal[4])[rs.getInt(4)] = rs.getBigDecimal(5);
            meta.put(key, new String[] {rs.getString(1), rs.getString(2), rs.getString(3)});
            return 1;
        }).list();
        List<Candidate> out = new ArrayList<>();
        LocalDate today = rows.today();
        windows.forEach((key, w) -> {
            String[] m = meta.get(key);
            BigDecimal current = w[0] == null ? BigDecimal.ZERO : w[0];
            List<BigDecimal> prev = new ArrayList<>();
            for (int i = 1; i < 4; i++) {
                prev.add(w[i]);
            }
            InsightRules.spendSpike(m[0] == null ? null : UUID.fromString(m[0]), m[1], m[2], current, prev, spikeMinExcess, today)
                    .ifPresent(out::add);
        });
        return out;
    }

    /** Expires open cards whose expiry passed by the end of the business date. */
    @Transactional
    public int expire(LocalDate businessDate) {
        List<ObjectNode> due = rows.query("""
                select row_to_json(t)::text from financial_insight t where t.expires_at < ?
                  and t.delivery_status in ('PENDING', 'DELIVERED', 'SEEN') for update""",
                businessDate.plusDays(1).atStartOfDay().atOffset(java.time.ZoneOffset.UTC));
        for (ObjectNode r : due) {
            ObjectNode v = rows.object();
            v.put("delivery_status", Lifecycles.INSIGHT.apply("expire", text(r, "delivery_status")));
            rows.update(T, Json.uuid(r, "id"), v);
        }
        return due.size();
    }
}
