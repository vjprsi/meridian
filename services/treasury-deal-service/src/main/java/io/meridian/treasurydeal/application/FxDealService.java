package io.meridian.treasurydeal.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.treasurydeal.application.Ports.Line;
import io.meridian.treasurydeal.application.SettlementService.Flow;
import io.meridian.treasurydeal.config.TreasuryProperties;
import io.meridian.treasurydeal.domain.Confirmations;
import io.meridian.treasurydeal.domain.Currencies;
import io.meridian.treasurydeal.domain.Exposures;
import io.meridian.treasurydeal.domain.FxMath;
import io.meridian.treasurydeal.domain.Lifecycle;
import io.meridian.treasurydeal.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Treasury FX deals (spot, forward, swap, NDF) with legs: capture with forward-point and limit checks, four-eyes
 * verification, confirmation, leg settlement with ledger postings, NDF fixing and net settlement, mark-to-market and
 * cancellation. Leg settlement posts per currency (balanced per currency):
 * <pre>  buy base: DR nostro(base) / CR fx-position(base);  DR fx-position(quote) / CR nostro(quote)</pre>
 */
@Service
public class FxDealService {
    static final String TABLE = "treasury_fx_deal";
    static final String LEGS = "treasury_fx_deal_leg";
    static final String DEAL_TYPE = "FX";
    static final Set<String> NEEDS_ISDA = Set.of("FORWARD", "SWAP", "NDF");

    private final Rows rows;
    private final Events events;
    private final CounterpartyService counterparties;
    private final LimitService limits;
    private final SettlementService settlements;
    private final Ports.Ledger ledger;
    private final Ports.MarketData market;
    private final TreasuryProperties props;
    private final ConfirmationGate gate;

    public FxDealService(Rows rows, Events events, CounterpartyService counterparties, LimitService limits,
                         SettlementService settlements, Ports.Ledger ledger, Ports.MarketData market,
                         TreasuryProperties props, ConfirmationGate gate) {
        this.rows = rows;
        this.events = events;
        this.counterparties = counterparties;
        this.limits = limits;
        this.settlements = settlements;
        this.ledger = ledger;
        this.market = market;
        this.props = props;
        this.gate = gate;
    }

    // ------------------------------------------------------------------ capture

    @Transactional
    public ObjectNode capture(JsonNode req) {
        ObjectNode v = rows.columns(TABLE, req);
        ObjectNode cp = counterparties.requireActive(Rows.uuid(v, "treasury_counterparty_id"));
        String type = Rows.str(v, "deal_type");
        Support.required(type, "deal_type");
        LocalDate trade = Rows.date(v, "trade_date");
        Support.required(trade, "trade_date");
        if (trade.isAfter(rows.today())) {
            throw ApiException.unprocessable("invalid_dates", "trade_date cannot be in the future");
        }
        if ("NDF".equals(type)) {
            v.put("non_deliverable", true);
        }
        boolean ndf = Rows.bool(v, "non_deliverable");
        if (NEEDS_ISDA.contains(type) && !Rows.bool(cp, "isda_signed")) {
            throw ApiException.unprocessable("isda_required", type + " deals need an ISDA master agreement with the counterparty");
        }
        JsonNode legsReq = req.get("legs");
        if (legsReq == null || !legsReq.isArray() || legsReq.isEmpty()) {
            throw ApiException.unprocessable("missing_field", "legs are required");
        }
        List<ObjectNode> legs = new ArrayList<>();
        legsReq.forEach(l -> legs.add(rows.columns(LEGS, l)));
        validateLegs(type, ndf, trade, legs, v);
        UUID id = Ids.newId();
        v.put("id", id.toString());
        v.put("deal_ref", Support.dealRef("FX", trade, id));
        v.put("status", "CAPTURED");
        ObjectNode deal = rows.insert(TABLE, v);
        for (ObjectNode l : legs) {
            l.put("treasury_fx_deal_id", id.toString());
            l.put("settlement_status", "PENDING");
            rows.insert(LEGS, l);
        }
        limits.reserve(DEAL_TYPE, id, Rows.uuid(cp, "id"), exposures(deal, legs(id)), trade);
        ObjectNode out = withLegs(deal);
        events.publish("treasury.fx_deal.captured", TABLE, out);
        return out;
    }

    private static void validateLegs(String type, boolean ndf, LocalDate trade, List<ObjectNode> legs, ObjectNode deal) {
        int expected = "SWAP".equals(type) ? 2 : 1;
        if (legs.size() != expected) {
            throw ApiException.unprocessable("invalid_legs", type + " deals have " + expected + " leg(s)");
        }
        if (ndf && !Set.of("NDF", "FORWARD").contains(type)) {
            throw ApiException.unprocessable("invalid_ndf", "Only forwards can be non-deliverable");
        }
        for (ObjectNode l : legs) {
            if (!l.hasNonNull("leg_type")) {
                l.put("leg_type", "NEAR");
            }
            String base = Rows.str(l, "base_currency");
            String quote = Rows.str(l, "quote_currency");
            Support.currency(base, "base_currency");
            Support.currency(quote, "quote_currency");
            if (base.equals(quote)) {
                throw ApiException.unprocessable("invalid_currency_pair", "base_currency and quote_currency must differ");
            }
            Support.required(Rows.str(l, "buy_sell"), "buy_sell");
            BigDecimal amount = Rows.dec(l, "base_amount");
            Support.positive(amount, "base_amount");
            Support.minorUnits(amount, base, "base_amount");
            BigDecimal rate = Rows.dec(l, "deal_rate");
            Support.positive(rate, "deal_rate");
            LocalDate valueDate = Rows.date(l, "value_date");
            Support.required(valueDate, "value_date");
            Support.ordered(trade, valueDate, "value_date must not be before trade_date");
            BigDecimal spot = Rows.dec(l, "spot_rate");
            BigDecimal points = Rows.dec(l, "forward_points");
            if (points != null && spot == null) {
                throw ApiException.unprocessable("missing_field", "spot_rate is required with forward_points");
            }
            if (spot != null && points != null && !FxMath.consistent(rate, spot, points, quote)) {
                throw ApiException.unprocessable("forward_points_mismatch", "deal_rate " + rate + " differs from spot "
                        + spot + " + " + points + " points = " + FxMath.outright(spot, points, quote));
            }
            l.put("quote_amount", FxMath.quoteAmount(amount, rate, quote).toPlainString());
        }
        LocalDate spotDate = spotDate(trade);
        ObjectNode near = legs.stream().filter(l -> "NEAR".equals(Rows.str(l, "leg_type"))).findFirst()
                .orElseThrow(() -> ApiException.unprocessable("invalid_legs", "A NEAR leg is required"));
        switch (type) {
            case "SPOT" -> {
                if (Rows.date(near, "value_date").isAfter(spotDate)) {
                    throw ApiException.unprocessable("invalid_value_date", "Spot deals settle by " + spotDate + " (T+2)");
                }
            }
            case "FORWARD", "NDF" -> {
                if (!Rows.date(near, "value_date").isAfter(spotDate)) {
                    throw ApiException.unprocessable("invalid_value_date", "Forwards settle after the spot date " + spotDate);
                }
            }
            case "SWAP" -> {
                ObjectNode far = legs.stream().filter(l -> "FAR".equals(Rows.str(l, "leg_type"))).findFirst()
                        .orElseThrow(() -> ApiException.unprocessable("invalid_legs", "Swaps need NEAR and FAR legs"));
                if (!Rows.str(near, "base_currency").equals(Rows.str(far, "base_currency"))
                        || !Rows.str(near, "quote_currency").equals(Rows.str(far, "quote_currency"))) {
                    throw ApiException.unprocessable("invalid_legs", "Both swap legs must trade the same currency pair");
                }
                if (Rows.str(near, "buy_sell").equals(Rows.str(far, "buy_sell"))) {
                    throw ApiException.unprocessable("invalid_legs", "Swap legs must be in opposite directions");
                }
                if (!Rows.date(far, "value_date").isAfter(Rows.date(near, "value_date"))) {
                    throw ApiException.unprocessable("invalid_value_date", "The FAR leg must settle after the NEAR leg");
                }
            }
            default -> throw ApiException.unprocessable("invalid_deal_type", "Unknown deal_type " + type);
        }
        if (ndf) {
            String settle = Rows.str(deal, "settlement_currency");
            Support.required(settle, "settlement_currency");
            if (!settle.equals(Rows.str(near, "base_currency")) && !settle.equals(Rows.str(near, "quote_currency"))) {
                throw ApiException.unprocessable("invalid_settlement_currency", "settlement_currency must be one of the pair");
            }
            LocalDate fixing = Rows.date(deal, "fixing_date");
            Support.required(fixing, "fixing_date");
            if (fixing.isAfter(Rows.date(near, "value_date")) || fixing.isBefore(trade)) {
                throw ApiException.unprocessable("invalid_dates", "fixing_date must fall between trade and value date");
            }
        }
    }

    /** Spot date: trade date plus two business days (weekends skipped). */
    static LocalDate spotDate(LocalDate trade) {
        LocalDate d = trade;
        int n = 0;
        while (n < 2) {
            d = d.plusDays(1);
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                n++;
            }
        }
        return d;
    }

    List<ObjectNode> legs(UUID dealId) {
        return rows.query("""
                select row_to_json(l)::text from treasury_fx_deal_leg l where treasury_fx_deal_id = ?
                order by case leg_type when 'NEAR' then 0 else 1 end""", dealId);
    }

    /** API representation: the deal row with its legs. */
    public ObjectNode withLegs(ObjectNode deal) {
        ObjectNode out = deal.deepCopy();
        ArrayNode arr = out.putArray("legs");
        legs(Rows.uuid(deal, "id")).forEach(arr::add);
        return out;
    }

    @Transactional(readOnly = true)
    public ObjectNode get(UUID id) {
        return withLegs(rows.get(TABLE, id));
    }

    static List<Exposures.Exposure> exposures(JsonNode deal, List<ObjectNode> legs) {
        List<Exposures.FxLeg> l = legs.stream().map(x -> new Exposures.FxLeg(Rows.str(x, "buy_sell"),
                Rows.str(x, "base_currency"), Rows.dec(x, "base_amount"), Rows.str(x, "quote_currency"),
                Rows.dec(x, "quote_amount"), Rows.date(x, "value_date"))).toList();
        return Exposures.fx(Rows.date(deal, "trade_date"), l, Rows.bool(deal, "non_deliverable"));
    }

    /** PATCH while CAPTURED: only descriptive fields (economics are amended by cancel and rebook). */
    @Transactional
    public ObjectNode amend(UUID id, int expectedVersion, JsonNode req) {
        ObjectNode deal = rows.lock(TABLE, id);
        if (Rows.integer(deal, "row_version") != expectedVersion) {
            throw ApiException.conflict("version_conflict", "If-Match does not match the current version; reload and retry");
        }
        if (!"CAPTURED".equals(Rows.str(deal, "status"))) {
            throw ApiException.conflict("deal_not_amendable", "Only CAPTURED deals can be amended");
        }
        for (String f : List.of("treasury_counterparty_id", "deal_type", "trade_date", "non_deliverable", "settlement_currency")) {
            if (req.hasNonNull(f)) {
                throw ApiException.unprocessable("field_not_updatable", f + " cannot be changed; cancel and rebook the deal");
            }
        }
        return withLegs(rows.update(TABLE, id, rows.columns(TABLE, req)));
    }

    // ------------------------------------------------------------------ lifecycle

    @Transactional
    public ObjectNode verify(UUID id) {
        ObjectNode deal = rows.lock(TABLE, id);
        Support.transition(Lifecycle.DEAL, deal, "VERIFIED", "deal");
        Support.fourEyes(deal, Rows.str(deal, "updated_by"));
        ObjectNode v = rows.object();
        v.put("status", "VERIFIED");
        v.put("verified_by", TenantContext.principal());
        v.put("verified_at", rows.now());
        ObjectNode out = withLegs(rows.update(TABLE, id, v));
        events.publish("treasury.fx_deal.verified", TABLE, out);
        return out;
    }

    @Transactional
    public ObjectNode confirm(UUID id, String manualReference) {
        ObjectNode deal = rows.lock(TABLE, id);
        Support.transition(Lifecycle.DEAL, deal, "CONFIRMED", "deal");
        gate.requireMatchedOrManual(DEAL_TYPE, id, Rows.str(deal, "deal_ref"), manualReference);
        return markConfirmed(id);
    }

    ObjectNode markConfirmed(UUID id) {
        ObjectNode v = rows.object();
        v.put("status", "CONFIRMED");
        v.put("confirmed_at", rows.now());
        ObjectNode out = withLegs(rows.update(TABLE, id, v));
        events.publish("treasury.fx_deal.confirmed", TABLE, out);
        return out;
    }

    /** Settles one leg on or after its value date; the deal becomes SETTLED with its last leg. */
    @Transactional
    public ObjectNode settle(UUID id, String legType) {
        Support.required(legType, "leg_type");
        ObjectNode deal = rows.lock(TABLE, id);
        if (!"CONFIRMED".equals(Rows.str(deal, "status"))) {
            throw ApiException.conflict("invalid_transition", "Only CONFIRMED deals settle (deal is " + Rows.str(deal, "status") + ")");
        }
        List<ObjectNode> legs = legs(id);
        ObjectNode leg = legs.stream().filter(l -> legType.equals(Rows.str(l, "leg_type"))).findFirst()
                .orElseThrow(() -> ApiException.unprocessable("unknown_leg", "The deal has no " + legType + " leg"));
        if (!"PENDING".equals(Rows.str(leg, "settlement_status"))) {
            throw ApiException.conflict("leg_already_settled", legType + " leg is " + Rows.str(leg, "settlement_status"));
        }
        if ("FAR".equals(legType) && legs.stream().anyMatch(l -> "NEAR".equals(Rows.str(l, "leg_type"))
                && "PENDING".equals(Rows.str(l, "settlement_status")))) {
            throw ApiException.conflict("near_leg_pending", "Settle the NEAR leg first");
        }
        LocalDate valueDate = Rows.date(leg, "value_date");
        if (valueDate.isAfter(rows.today())) {
            throw ApiException.unprocessable("value_date_not_reached", "The leg settles on " + valueDate);
        }
        UUID legId = Rows.uuid(leg, "id");
        String ref = Rows.str(deal, "deal_ref");
        UUID cp = Rows.uuid(deal, "treasury_counterparty_id");
        UUID je = null;
        if (Rows.bool(deal, "non_deliverable")) {
            BigDecimal net = Rows.dec(deal, "ndf_settlement_amount");
            if (net == null) {
                throw ApiException.unprocessable("fixing_required", "Record the NDF fixing before settlement");
            }
            String ccy = Rows.str(deal, "settlement_currency");
            if (net.signum() != 0) {
                BigDecimal a = net.abs();
                je = ledger.post(new Ports.Posting("fx-settle:" + legId, "FX", "NDF " + ref + " net settlement",
                        "TREASURY_FX_DEAL", id, valueDate, ref, net.signum() > 0
                        ? List.of(Line.debit(ccy, "nostro", a), Line.credit(ccy, "realised-pnl", a))
                        : List.of(Line.debit(ccy, "realised-pnl", a), Line.credit(ccy, "nostro", a))));
                settlements.create(DEAL_TYPE, id, cp, null, je, List.of(net.signum() > 0
                        ? Flow.receive("NDF_RECEIVE", a, ccy, valueDate) : Flow.pay("NDF_PAY", a, ccy, valueDate)));
            }
        } else {
            boolean buy = "BUY".equals(Rows.str(leg, "buy_sell"));
            String base = Rows.str(leg, "base_currency");
            String quote = Rows.str(leg, "quote_currency");
            BigDecimal baseAmt = Rows.dec(leg, "base_amount");
            BigDecimal quoteAmt = Rows.dec(leg, "quote_amount");
            String inCcy = buy ? base : quote;
            String outCcy = buy ? quote : base;
            BigDecimal inAmt = buy ? baseAmt : quoteAmt;
            BigDecimal outAmt = buy ? quoteAmt : baseAmt;
            je = ledger.post(new Ports.Posting("fx-settle:" + legId, "FX", "FX " + ref + " " + legType + " leg settlement",
                    "TREASURY_FX_DEAL", id, valueDate, ref, List.of(
                    Line.debit(inCcy, "nostro", inAmt), Line.credit(inCcy, "fx-position", inAmt),
                    Line.debit(outCcy, "fx-position", outAmt), Line.credit(outCcy, "nostro", outAmt))));
            settlements.create(DEAL_TYPE, id, cp, null, je, List.of(
                    Flow.receive("FX_RECEIVE", inAmt, inCcy, valueDate), Flow.pay("FX_PAY", outAmt, outCcy, valueDate)));
        }
        ObjectNode lv = rows.object();
        lv.put("settlement_status", "SETTLED");
        if (je != null) {
            lv.put("journal_entry_id", je.toString());
        }
        rows.update(LEGS, legId, lv);
        boolean allSettled = legs(id).stream().allMatch(l -> "SETTLED".equals(Rows.str(l, "settlement_status")));
        ObjectNode out;
        if (allSettled) {
            ObjectNode v = rows.object();
            v.put("status", "SETTLED");
            out = withLegs(rows.update(TABLE, id, v));
            limits.release(DEAL_TYPE, id, false);
        } else {
            out = withLegs(rows.get(TABLE, id));
        }
        events.publish("treasury.fx_deal.settled", TABLE, out);
        return out;
    }

    /** Records the NDF fixing (on or after the fixing date) and the net settlement amount. */
    @Transactional
    public ObjectNode fix(UUID id, BigDecimal fixingRate) {
        Support.positive(fixingRate, "fixing_rate");
        ObjectNode deal = rows.lock(TABLE, id);
        if (!Rows.bool(deal, "non_deliverable")) {
            throw ApiException.unprocessable("not_ndf", "Only non-deliverable deals are fixed");
        }
        if (!Set.of("VERIFIED", "CONFIRMED").contains(Rows.str(deal, "status"))) {
            throw ApiException.conflict("invalid_transition", "The deal must be VERIFIED or CONFIRMED to be fixed");
        }
        LocalDate fixingDate = Rows.date(deal, "fixing_date");
        if (fixingDate != null && fixingDate.isAfter(rows.today())) {
            throw ApiException.unprocessable("fixing_date_not_reached", "The deal fixes on " + fixingDate);
        }
        ObjectNode near = legs(id).getFirst();
        BigDecimal net = FxMath.ndfSettlement(Rows.str(near, "buy_sell"), Rows.dec(near, "base_amount"),
                Rows.dec(near, "deal_rate"), fixingRate, Rows.str(near, "base_currency"), Rows.str(deal, "settlement_currency"));
        ObjectNode v = rows.object();
        v.put("fixing_rate", fixingRate.toPlainString());
        v.put("ndf_settlement_amount", net.toPlainString());
        ObjectNode out = withLegs(rows.update(TABLE, id, v));
        events.publish("treasury.fx_deal.fixed", TABLE, out);
        return out;
    }

    /** Undiscounted MTM of the unsettled legs in the near leg's quote currency. */
    @Transactional
    public ObjectNode revalue(UUID id, LocalDate valuationDate, BigDecimal marketRate) {
        Support.required(valuationDate, "valuation_date");
        ObjectNode deal = rows.lock(TABLE, id);
        if (Set.of("CANCELLED", "SETTLED", "MATURED").contains(Rows.str(deal, "status"))) {
            throw ApiException.conflict("invalid_transition", "A " + Rows.str(deal, "status") + " deal is not revalued");
        }
        List<ObjectNode> legs = legs(id);
        ObjectNode near = legs.getFirst();
        String base = Rows.str(near, "base_currency");
        String quote = Rows.str(near, "quote_currency");
        BigDecimal rate = marketRate != null ? marketRate : marketRate(base, quote)
                .orElseThrow(() -> ApiException.unprocessable("market_rate_unavailable",
                        "No " + base + "/" + quote + " revaluation rate; pass market_rate"));
        Support.positive(rate, "market_rate");
        BigDecimal mtm = BigDecimal.ZERO;
        for (ObjectNode l : legs) {
            if ("PENDING".equals(Rows.str(l, "settlement_status"))) {
                mtm = mtm.add(FxMath.legMtm(Rows.str(l, "buy_sell"), Rows.dec(l, "base_amount"), Rows.dec(l, "deal_rate"),
                        rate, quote));
            }
        }
        ObjectNode v = rows.object();
        v.put("mtm_value", Currencies.round(mtm, quote).toPlainString());
        v.put("mtm_currency", quote);
        v.put("mtm_date", valuationDate.toString());
        ObjectNode out = withLegs(rows.update(TABLE, id, v));
        events.publish("treasury.fx_deal.revalued", TABLE, out);
        return out;
    }

    Optional<BigDecimal> marketRate(String base, String quote) {
        return market.rate(base, quote, "REVALUATION").or(() -> market.rate(base, quote, "MID"));
    }

    @Transactional
    public ObjectNode cancel(UUID id, String reason) {
        Support.required(reason, "reason");
        ObjectNode deal = rows.lock(TABLE, id);
        Support.transition(Lifecycle.DEAL, deal, "CANCELLED", "deal");
        if (legs(id).stream().anyMatch(l -> !"PENDING".equals(Rows.str(l, "settlement_status")))) {
            throw ApiException.conflict("leg_already_settled", "A deal with a settled leg cannot be cancelled");
        }
        ObjectNode v = rows.object();
        v.put("status", "CANCELLED");
        v.put("cancel_reason", reason);
        ObjectNode out = withLegs(rows.update(TABLE, id, v));
        limits.release(DEAL_TYPE, id, false);
        settlements.cancelOpen(id);
        events.publish("treasury.fx_deal.cancelled", TABLE, out);
        return out;
    }

    /** End-of-day PRE_SETTLEMENT refresh: notional x add-on for the residual tenor plus positive MTM. */
    void refreshPreSettlement(JsonNode deal, LocalDate businessDate) {
        List<ObjectNode> legs = legs(Rows.uuid(deal, "id"));
        BigDecimal notional = BigDecimal.ZERO;
        LocalDate last = businessDate;
        for (ObjectNode l : legs) {
            if ("PENDING".equals(Rows.str(l, "settlement_status"))) {
                notional = notional.max(Rows.dec(l, "base_amount"));
                if (Rows.date(l, "value_date").isAfter(last)) {
                    last = Rows.date(l, "value_date");
                }
            }
        }
        String base = Rows.str(legs.getFirst(), "base_currency");
        BigDecimal pse = notional.multiply(FxMath.pfeAddOn(true, ChronoUnit.DAYS.between(businessDate, last)));
        BigDecimal mtm = Rows.dec(deal, "mtm_value");
        if (mtm != null && mtm.signum() > 0) {
            pse = pse.add(limits.convert(mtm, Rows.str(deal, "mtm_currency"), base));
        }
        limits.refreshPreSettlement(DEAL_TYPE, Rows.uuid(deal, "id"), Rows.uuid(deal, "treasury_counterparty_id"), pse,
                base, businessDate);
    }

    // ------------------------------------------------------------------ confirmation text

    public Confirmations.Fx confirmation(JsonNode deal) {
        JsonNode cp = rows.get(CounterpartyService.TABLE, Rows.uuid(deal, "treasury_counterparty_id"));
        List<Confirmations.FxLeg> legs = legs(Rows.uuid(deal, "id")).stream().map(l -> {
            boolean buy = "BUY".equals(Rows.str(l, "buy_sell"));
            return new Confirmations.FxLeg(Rows.str(l, "leg_type"), Rows.date(l, "value_date"), Rows.dec(l, "deal_rate"),
                    buy ? Rows.str(l, "base_currency") : Rows.str(l, "quote_currency"),
                    buy ? Rows.dec(l, "base_amount") : Rows.dec(l, "quote_amount"),
                    buy ? Rows.str(l, "quote_currency") : Rows.str(l, "base_currency"),
                    buy ? Rows.dec(l, "quote_amount") : Rows.dec(l, "base_amount"));
        }).toList();
        return new Confirmations.Fx(Rows.str(deal, "deal_ref"), props.ourBic(), Rows.str(cp, "bic"),
                Rows.date(deal, "trade_date"), legs, Rows.bool(deal, "non_deliverable"),
                Rows.str(deal, "settlement_currency"), Rows.date(deal, "fixing_date"));
    }

    @Transactional(readOnly = true)
    public String confirmationMessage(UUID id) {
        return Confirmations.mt300(confirmation(rows.get(TABLE, id)));
    }
}
