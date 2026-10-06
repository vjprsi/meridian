package io.meridian.insights.application;

import static io.meridian.insights.application.Json.text;

import io.meridian.common.error.ApiException;
import io.meridian.insights.api.generated.model.RecurringPayment;
import io.meridian.insights.domain.InsightRules;
import io.meridian.insights.domain.Lifecycles;
import io.meridian.insights.domain.RecurringDetector;
import io.meridian.insights.infrastructure.ExternalServices;
import io.meridian.insights.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
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
 * Recurring payments (subscriptions, bills, salary): cadence detection over the customer's enriched history, linking
 * of new transactions to known series with price-change detection, the customer actions (confirm, dismiss, block the
 * merchant on the card) and retirement of series whose expected charge did not arrive.
 */
@Service
public class RecurringService {
    static final String T = "recurring_payment";
    static final int DEFAULT_LOOKBACK = 400;

    private final Rows rows;
    private final Events events;
    private final InsightCards cards;
    private final ExternalServices external;

    public RecurringService(Rows rows, Events events, InsightCards cards, ExternalServices external) {
        this.rows = rows;
        this.events = events;
        this.cards = cards;
        this.external = external;
    }

    /**
     * Finds the known series a new transaction belongs to (same customer, merchant, currency and direction, amount
     * within the link tolerance) and, when the transaction is the newest charge, rolls the series forward.
     */
    public Optional<ObjectNode> link(UUID customerId, String merchant, String currency, BigDecimal amount, OffsetDateTime at) {
        if (merchant == null || amount == null || amount.signum() == 0) {
            return Optional.empty();
        }
        boolean income = amount.signum() > 0;
        for (ObjectNode rp : rows.query("""
                select row_to_json(t)::text from recurring_payment t where t.customer_id = ? and lower(t.merchant_clean_name) = lower(?)
                  and t.currency = ? and t.is_income = ? and t.status in ('DETECTED', 'CONFIRMED', 'INACTIVE')
                order by t.id for update""", customerId, merchant, currency, income)) {
            BigDecimal typical = Json.decimal(rp, "typical_amount");
            if (!RecurringDetector.within(typical, amount.abs(), RecurringDetector.LINK_TOLERANCE)) {
                continue;
            }
            UUID id = Json.uuid(rp, "id");
            OffsetDateTime lastSeen = Json.ts(rp, "last_seen_at");
            if (lastSeen != null && !at.isAfter(lastSeen)) {
                return Optional.of(rp);
            }
            ObjectNode v = rows.object();
            BigDecimal last = Json.decimal(rp, "last_amount");
            boolean changed = RecurringDetector.changed(last, amount.abs());
            v.put("last_amount", amount.abs().toPlainString());
            v.put("last_seen_at", at.toString());
            v.put("occurrences", rp.path("occurrences").asInt(0) + 1);
            v.put("next_expected_at", RecurringDetector.next(text(rp, "frequency"), at.toLocalDate())
                    .atStartOfDay().atOffset(ZoneOffset.UTC).toString());
            if ("INACTIVE".equals(text(rp, "status"))) {
                v.put("status", "DETECTED");
            }
            if (changed) {
                v.put("previous_amount", last.toPlainString());
                v.put("price_changed_at", rows.now().toString());
            }
            ObjectNode row = rows.update(T, id, v);
            if (changed) {
                priceChanged(row, last, amount.abs());
            }
            return Optional.of(row);
        }
        return Optional.empty();
    }

    private void priceChanged(ObjectNode row, BigDecimal previous, BigDecimal current) {
        events.publish("engagement.recurring_payment.price_changed", T, row, RecurringPayment.class);
        if (!row.path("is_income").asBoolean(false)) {
            cards.raise(Json.uuid(row, "customer_id"), InsightRules.priceChange(Json.uuid(row, "id"),
                    text(row, "merchant_clean_name"), text(row, "currency"), previous, current), true);
        }
    }

    /** Detects recurring series in the customer's current enrichments over the lookback window and upserts them. */
    @Transactional
    public JsonNode detect(UUID customerId, Integer lookbackDays) {
        int lookback = lookbackDays == null ? DEFAULT_LOOKBACK : lookbackDays;
        if (lookback < 30 || lookback > 1100) {
            throw new ApiException(422, "invalid_lookback", "lookback_days must be between 30 and 1100",
                    List.of(new ApiException.FieldError("lookback_days", "out_of_range", "30..1100")));
        }
        record Tx(LocalDate date, BigDecimal amount, String name, String currency, String category, String source,
                  String accountRef, String merchantId) {}
        Map<String, List<Tx>> groups = new LinkedHashMap<>();
        rows.jdbc().sql("""
                select t.transaction_at, t.amount, t.merchant_clean_name, t.currency, t.spend_category_id, t.source_type,
                       t.account_ref, t.merchant_id
                from transaction_enrichment t
                where t.customer_id = ? and t.merchant_clean_name is not null and t.amount <> 0
                  and t.transaction_at > now() - make_interval(days => ?) and t.transaction_at <= now()
                  and not exists (select 1 from transaction_enrichment s where s.supersedes_id = t.id)
                order by t.transaction_at""").params(customerId, lookback).query((rs, i) -> {
            Tx tx = new Tx(rs.getObject(1, OffsetDateTime.class).withOffsetSameInstant(ZoneOffset.UTC).toLocalDate(),
                    rs.getBigDecimal(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                    rs.getString(8));
            String key = tx.name().toLowerCase() + "|" + tx.currency() + "|" + (tx.amount().signum() > 0 ? "IN" : "OUT");
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(tx);
            return 1;
        }).list();
        int created = 0;
        int updated = 0;
        ArrayNode series = rows.json().createArrayNode();
        for (List<Tx> txs : groups.values()) {
            Optional<RecurringDetector.Detection> d = RecurringDetector.detect(
                    txs.stream().map(t -> new RecurringDetector.Charge(t.date(), t.amount())).toList());
            if (d.isEmpty()) {
                continue;
            }
            RecurringDetector.Detection det = d.get();
            Tx last = txs.getLast();
            boolean income = last.amount().signum() > 0;
            Map<String, Integer> catVotes = new HashMap<>();
            txs.forEach(t -> {
                if (t.category() != null) {
                    catVotes.merge(t.category(), 1, Integer::sum);
                }
            });
            String category = catVotes.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
            ObjectNode v = rows.object();
            v.put("frequency", det.frequency());
            v.put("typical_amount", det.typicalAmount().toPlainString());
            v.put("last_amount", det.lastAmount().toPlainString());
            v.put("first_seen_at", det.firstSeen().atStartOfDay().atOffset(ZoneOffset.UTC).toString());
            v.put("last_seen_at", det.lastSeen().atStartOfDay().atOffset(ZoneOffset.UTC).toString());
            v.put("next_expected_at", det.nextExpected().atStartOfDay().atOffset(ZoneOffset.UTC).toString());
            v.put("occurrences", det.occurrences());
            v.put("detection_confidence", det.confidence().toPlainString());
            Optional<ObjectNode> existing = rows.queryOne("""
                    select row_to_json(t)::text from recurring_payment t where t.customer_id = ?
                      and lower(t.merchant_clean_name) = lower(?) and t.currency = ? and t.is_income = ?
                    order by t.id limit 1 for update""", customerId, last.name(), last.currency(), income);
            ObjectNode row;
            if (existing.isPresent()) {
                ObjectNode rp = existing.get();
                BigDecimal prevLast = Json.decimal(rp, "last_amount");
                OffsetDateTime prevSeen = Json.ts(rp, "last_seen_at");
                boolean newer = prevSeen == null || det.lastSeen().isAfter(prevSeen.toLocalDate());
                boolean changed = newer && RecurringDetector.changed(prevLast, det.lastAmount());
                if (changed) {
                    v.put("previous_amount", prevLast.toPlainString());
                    v.put("price_changed_at", rows.now().toString());
                } else if (det.priceChanged() && text(rp, "price_changed_at") == null) {
                    v.put("previous_amount", det.previousAmount().toPlainString());
                    v.put("price_changed_at", rows.now().toString());
                    changed = true;
                    prevLast = det.previousAmount();
                }
                if ("INACTIVE".equals(text(rp, "status")) && newer) {
                    v.put("status", "DETECTED");
                }
                row = rows.update(T, Json.uuid(rp, "id"), v);
                if (changed) {
                    priceChanged(row, prevLast, det.lastAmount());
                }
                updated++;
            } else {
                v.put("customer_id", customerId.toString());
                v.put("merchant_clean_name", last.name());
                if (last.merchantId() != null) {
                    v.put("merchant_id", last.merchantId());
                }
                if (category != null) {
                    v.put("spend_category_id", category);
                }
                v.put("is_income", income);
                v.put("currency", last.currency());
                v.put("status", "DETECTED");
                if (last.accountRef() != null && last.source() != null && last.source().startsWith("CARD_")) {
                    v.put("card_id", last.accountRef());
                }
                if (det.priceChanged()) {
                    v.put("previous_amount", det.previousAmount().toPlainString());
                    v.put("price_changed_at", rows.now().toString());
                }
                row = rows.insert(T, v);
                events.publish("engagement.recurring_payment.detected", T, row, RecurringPayment.class);
                if (det.priceChanged()) {
                    priceChanged(row, det.previousAmount(), det.lastAmount());
                }
                cards.raise(customerId, InsightRules.newSeries(Json.uuid(row, "id"), last.name(), det.frequency(),
                        last.currency(), det.typicalAmount(), income, det.lastSeen()), true);
                created++;
            }
            series.add(rows.json().valueToTree(rows.read(row, RecurringPayment.class)));
        }
        ObjectNode out = rows.object();
        out.put("customer_id", customerId.toString());
        out.put("lookback_days", lookback);
        out.put("merchants_analysed", groups.size());
        out.put("detected_new", created);
        out.put("updated", updated);
        out.set("series", series);
        return out;
    }

    @Transactional
    public ObjectNode transition(UUID id, String action) {
        ObjectNode rp = rows.lock(T, id);
        ObjectNode v = rows.object();
        v.put("status", Lifecycles.RECURRING.apply(action, text(rp, "status")));
        ObjectNode row = rows.update(T, id, v);
        events.publish("confirm".equals(action) ? "engagement.recurring_payment.confirmed"
                : "engagement.recurring_payment.dismissed", T, row, RecurringPayment.class);
        return row;
    }

    /** Blocks future charges of the merchant on the card through a card-service MERCHANT_DENY control. */
    @Transactional
    public ObjectNode blockCardCharges(UUID id, UUID cardId, String merchantRef) {
        ObjectNode rp = rows.lock(T, id);
        String to = Lifecycles.RECURRING.apply("block", text(rp, "status"));
        if (rp.path("is_income").asBoolean(false)) {
            throw ApiException.unprocessable("not_a_payment", "Recurring income cannot be blocked on a card");
        }
        UUID card = cardId != null ? cardId : Json.uuid(rp, "card_id");
        String merchant = merchantRef != null && !merchantRef.isBlank() ? merchantRef.trim() : text(rp, "merchant_id");
        if (card == null) {
            throw ApiException.unprocessable("card_required", "No card is known for this subscription; give card_id");
        }
        if (merchant == null) {
            throw ApiException.unprocessable("merchant_ref_required",
                    "No merchant id is known for this subscription; give merchant_ref (card acceptor id)");
        }
        UUID control = external.blockMerchantOnCard(card, merchant, "Subscription " + text(rp, "merchant_clean_name")
                + " cancelled by the customer", "insights-block:" + id);
        ObjectNode v = rows.object();
        v.put("status", to);
        v.put("card_id", card.toString());
        v.put("card_control_id", control.toString());
        ObjectNode row = rows.update(T, id, v);
        events.publish("engagement.recurring_payment.cancelled", T, row, RecurringPayment.class);
        return row;
    }

    /** Retires DETECTED / CONFIRMED series whose expected charge is overdue by more than the grace period. */
    @Transactional
    public int retireStale(LocalDate businessDate) {
        int n = 0;
        for (ObjectNode rp : rows.query("""
                select row_to_json(t)::text from recurring_payment t where t.status in ('DETECTED', 'CONFIRMED')
                  and t.next_expected_at is not null and t.next_expected_at < ? order by t.id for update""",
                businessDate.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC))) {
            LocalDate expected = Json.ts(rp, "next_expected_at").toLocalDate();
            if (expected.plusDays(RecurringDetector.graceDays(text(rp, "frequency"))).isBefore(businessDate)) {
                ObjectNode v = rows.object();
                v.put("status", Lifecycles.RECURRING.apply("retire", text(rp, "status")));
                ObjectNode row = rows.update(T, Json.uuid(rp, "id"), v);
                events.publish("engagement.recurring_payment.inactive", T, row, RecurringPayment.class);
                n++;
            }
        }
        return n;
    }
}
