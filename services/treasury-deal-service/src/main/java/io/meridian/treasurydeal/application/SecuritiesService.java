package io.meridian.treasurydeal.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.treasurydeal.application.Ports.Line;
import io.meridian.treasurydeal.application.SettlementService.Flow;
import io.meridian.treasurydeal.domain.BondMath;
import io.meridian.treasurydeal.domain.Currencies;
import io.meridian.treasurydeal.domain.DayCount;
import io.meridian.treasurydeal.domain.Exposures;
import io.meridian.treasurydeal.domain.Identifiers;
import io.meridian.treasurydeal.domain.Lifecycle;
import io.meridian.treasurydeal.infrastructure.Rows;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Securities: master data (ISIN check digit), prices and analytics (accrued, dirty price, simple yield), trades that
 * open or change holdings (weighted average cost, realised P&L, HTM sale restriction), holding revaluation and
 * encumbrance. Settlement posts: buy DR securities (clean) + DR interest-receivable (accrued) / CR nostro (dirty);
 * sell DR nostro / CR securities (book cost), CR interest-receivable, realised P&L to realised-pnl.
 */
@Service
public class SecuritiesService {
    static final String SECURITY = "treasury_security";
    static final String HOLDING = "securities_holding";
    static final String TRADE = "securities_trade";
    static final String DEAL_TYPE = "SECURITY";
    static final MathContext MC = MathContext.DECIMAL128;
    static final Set<String> DISCOUNT = Set.of("TREASURY_BILL", "COMMERCIAL_PAPER");

    private final Rows rows;
    private final Events events;
    private final CounterpartyService counterparties;
    private final LimitService limits;
    private final SettlementService settlements;
    private final Ports.Ledger ledger;

    public SecuritiesService(Rows rows, Events events, CounterpartyService counterparties, LimitService limits,
                             SettlementService settlements, Ports.Ledger ledger) {
        this.rows = rows;
        this.events = events;
        this.counterparties = counterparties;
        this.limits = limits;
        this.settlements = settlements;
        this.ledger = ledger;
    }

    // ------------------------------------------------------------------ security master

    @Transactional
    public ObjectNode createSecurity(JsonNode req) {
        ObjectNode v = rows.columns(SECURITY, req);
        validateSecurity(v);
        if (rows.scalar("select count(*) from treasury_security where isin = ?", Integer.class, Rows.str(v, "isin"))
                .orElse(0) > 0) {
            throw ApiException.conflict("duplicate_isin", "Security " + Rows.str(v, "isin") + " already exists");
        }
        return rows.insert(SECURITY, v);
    }

    @Transactional
    public ObjectNode updateSecurity(UUID id, int expectedVersion, JsonNode req) {
        ObjectNode sec = rows.lock(SECURITY, id);
        if (Rows.integer(sec, "row_version") != expectedVersion) {
            throw ApiException.conflict("version_conflict", "If-Match does not match the current version; reload and retry");
        }
        for (String f : List.of("isin", "currency")) {
            if (req.hasNonNull(f) && !req.get(f).asString().equals(Rows.str(sec, f))) {
                throw ApiException.unprocessable("field_not_updatable", f + " of a security cannot be changed");
            }
        }
        ObjectNode changes = rows.columns(SECURITY, req);
        ObjectNode merged = sec.deepCopy();
        merged.setAll(changes);
        validateSecurity(merged);
        return rows.update(SECURITY, id, changes);
    }

    private void validateSecurity(ObjectNode v) {
        String isin = Rows.str(v, "isin");
        if (!Identifiers.isValidIsin(isin)) {
            throw ApiException.unprocessable("invalid_isin", "isin " + isin + " is not a valid ISIN (format or check digit)");
        }
        Support.currency(Rows.str(v, "currency"), "currency");
        String type = Rows.str(v, "security_type");
        BigDecimal coupon = Rows.dec(v, "coupon_rate");
        Integer freq = Rows.integer(v, "coupon_frequency");
        if (DISCOUNT.contains(type)) {
            if (coupon != null && coupon.signum() != 0) {
                throw ApiException.unprocessable("invalid_coupon", type + " is a discount instrument without coupon");
            }
        } else if (coupon != null && coupon.signum() != 0) {
            if (freq == null || !Set.of(1, 2, 4, 12).contains(freq)) {
                throw ApiException.unprocessable("invalid_coupon_frequency", "coupon_frequency must be 1, 2, 4 or 12");
            }
            if (coupon.signum() < 0 || coupon.compareTo(BigDecimal.ONE) > 0) {
                throw ApiException.unprocessable("invalid_coupon", "coupon_rate is a decimal annual rate between 0 and 1");
            }
        }
        Support.required(Rows.date(v, "maturity_date"), "maturity_date");
        LocalDate issue = Rows.date(v, "issue_date");
        if (issue != null && !Rows.date(v, "maturity_date").isAfter(issue)) {
            throw ApiException.unprocessable("invalid_dates", "maturity_date must be after issue_date");
        }
        UUID issuer = Rows.uuid(v, "issuer_counterparty_id");
        if (issuer != null) {
            rows.get(CounterpartyService.TABLE, issuer);
        }
    }

    static BondMath.Terms terms(JsonNode sec) {
        Integer freq = Rows.integer(sec, "coupon_frequency");
        return new BondMath.Terms(Rows.dec(sec, "coupon_rate"), freq == null ? 1 : freq, Rows.date(sec, "issue_date"),
                Rows.date(sec, "maturity_date"), DayCount.parse(Rows.str(sec, "day_count")));
    }

    @Transactional
    public ObjectNode recordPrice(UUID id, BigDecimal price, LocalDate priceDate) {
        Support.positive(price, "price");
        Support.required(priceDate, "price_date");
        ObjectNode sec = rows.lock(SECURITY, id);
        if (priceDate.isAfter(rows.today())) {
            throw ApiException.unprocessable("invalid_dates", "price_date cannot be in the future");
        }
        LocalDate last = Rows.date(sec, "last_price_date");
        if (last != null && priceDate.isBefore(last)) {
            throw ApiException.conflict("stale_price", "A price for " + last + " is already recorded");
        }
        ObjectNode v = rows.object();
        v.put("last_price", price.toPlainString());
        v.put("last_price_date", priceDate.toString());
        ObjectNode out = rows.update(SECURITY, id, v);
        events.publish("treasury.security.priced", SECURITY, out);
        return out;
    }

    @Transactional(readOnly = true)
    public ObjectNode analytics(UUID id, BigDecimal cleanPrice, LocalDate settlementDate, BigDecimal nominal) {
        Support.positive(cleanPrice, "clean_price");
        Support.required(settlementDate, "settlement_date");
        ObjectNode sec = rows.get(SECURITY, id);
        BondMath.Terms t = terms(sec);
        if (!settlementDate.isBefore(t.maturity())) {
            throw ApiException.unprocessable("invalid_dates", "settlement_date must be before maturity");
        }
        String ccy = Rows.str(sec, "currency");
        ObjectNode out = rows.object();
        out.put("isin", Rows.str(sec, "isin"));
        out.put("settlement_date", settlementDate.toString());
        out.put("clean_price", cleanPrice.toPlainString());
        out.put("day_count", Rows.str(sec, "day_count"));
        BigDecimal accruedPct = BondMath.accruedPct(t, settlementDate);
        out.put("accrued_interest_pct", accruedPct.setScale(10, RoundingMode.HALF_EVEN).toPlainString());
        out.put("dirty_price", BondMath.dirtyPrice(t, cleanPrice, settlementDate).toPlainString());
        out.put("simple_yield", BondMath.simpleYield(t, cleanPrice, settlementDate).toPlainString());
        out.put("current_yield", t.couponRate() == null ? "0" : t.couponRate().multiply(BigDecimal.valueOf(100))
                .divide(cleanPrice, MC).setScale(10, RoundingMode.HALF_EVEN).toPlainString());
        if (t.couponRate() != null && t.couponRate().signum() != 0) {
            out.put("previous_coupon_date", BondMath.previousCoupon(t, settlementDate).toString());
            out.put("next_coupon_date", BondMath.nextCoupon(t, settlementDate).toString());
        }
        out.put("days_to_maturity", ChronoUnit.DAYS.between(settlementDate, t.maturity()));
        if (nominal != null) {
            Support.positive(nominal, "nominal");
            BigDecimal clean = BondMath.cleanConsideration(nominal, cleanPrice, ccy);
            BigDecimal accrued = BondMath.accruedAmount(t, nominal, settlementDate, ccy);
            out.put("nominal", nominal.toPlainString());
            out.put("clean_consideration", clean.toPlainString());
            out.put("accrued_amount", accrued.toPlainString());
            out.put("settlement_amount", clean.add(accrued).toPlainString());
            out.put("currency", ccy);
        }
        return out;
    }

    // ------------------------------------------------------------------ trades

    @Transactional
    public ObjectNode captureTrade(JsonNode req) {
        ObjectNode v = rows.columns(TRADE, req);
        UUID secId = Rows.uuid(v, "treasury_security_id");
        Support.required(secId, "treasury_security_id");
        ObjectNode sec = rows.find(SECURITY, secId).orElseThrow(() -> ApiException.unprocessable("unknown_security",
                "treasury security " + secId + " does not exist"));
        ObjectNode cp = counterparties.requireActive(Rows.uuid(v, "treasury_counterparty_id"));
        String ccy = Rows.str(sec, "currency");
        BigDecimal nominal = Rows.dec(v, "nominal");
        BigDecimal clean = Rows.dec(v, "clean_price");
        Support.positive(nominal, "nominal");
        Support.minorUnits(nominal, ccy, "nominal");
        Support.positive(clean, "clean_price");
        LocalDate trade = Rows.date(v, "trade_date");
        LocalDate settle = Rows.date(v, "settlement_date");
        Support.required(trade, "trade_date");
        Support.required(settle, "settlement_date");
        if (trade.isAfter(rows.today(Rows.uuid(v, "legal_entity_id")))) {
            throw ApiException.unprocessable("invalid_dates", "trade_date cannot be in the future");
        }
        Support.ordered(trade, settle, "settlement_date must not be before trade_date");
        BondMath.Terms t = terms(sec);
        if (!settle.isBefore(t.maturity())) {
            throw ApiException.unprocessable("invalid_dates", "The security matures on or before the settlement date");
        }
        String side = Rows.str(v, "buy_sell");
        Support.required(side, "buy_sell");
        Support.required(Rows.str(v, "portfolio"), "portfolio");
        String cls = Rows.str(v, "accounting_class");
        Support.required(cls, "accounting_class");
        ObjectNode holding = holdingFor(Rows.uuid(v, "legal_entity_id"), secId, Rows.str(v, "portfolio"), cls, "BUY".equals(side));
        if ("SELL".equals(side)) {
            checkSale(holding, nominal, cls);
        }
        BigDecimal accrued = BondMath.accruedAmount(t, nominal, settle, ccy);
        BigDecimal consideration = BondMath.cleanConsideration(nominal, clean, ccy);
        UUID id = Ids.newId();
        v.put("id", id.toString());
        v.put("deal_ref", Support.dealRef("SC", trade, id));
        v.put("securities_holding_id", Rows.str(holding, "id"));
        v.put("currency", ccy);
        v.put("accrued_interest", accrued.toPlainString());
        v.put("dirty_price", BondMath.dirtyPrice(t, clean, settle).toPlainString());
        v.put("settlement_amount", consideration.add(accrued).toPlainString());
        v.put("simple_yield", BondMath.simpleYield(t, clean, settle).toPlainString());
        v.put("status", "PENDING");
        ObjectNode out = rows.insert(TRADE, v);
        UUID issuer = Rows.uuid(sec, "issuer_counterparty_id");
        if ("BUY".equals(side) && issuer != null) {
            limits.reserve(DEAL_TYPE, id, issuer, Exposures.securityPurchase(consideration, ccy, settle, t.maturity()), trade);
        }
        events.publish("treasury.securities_trade.captured", TRADE, out);
        return out;
    }

    /** Finds the holding of the key, opening an empty one for purchases. */
    private ObjectNode holdingFor(UUID legalEntityId, UUID securityId, String portfolio, String cls, boolean open) {
        Support.required(legalEntityId, "legal_entity_id");
        return rows.queryOne("""
                select row_to_json(h)::text from securities_holding h
                where treasury_security_id = ? and portfolio = ? and accounting_class = ?""", securityId, portfolio, cls)
                .orElseGet(() -> {
                    if (!open) {
                        throw ApiException.unprocessable("insufficient_holding", "No holding of the security in " + portfolio + "/" + cls);
                    }
                    ObjectNode h = rows.object();
                    h.put("legal_entity_id", legalEntityId.toString());
                    h.put("treasury_security_id", securityId.toString());
                    h.put("portfolio", portfolio);
                    h.put("accounting_class", cls);
                    h.put("nominal", "0");
                    h.put("book_value", "0");
                    return rows.insert(HOLDING, h);
                });
    }

    /** A sale needs an unencumbered holding with enough nominal net of pending sales; HTM positions cannot be sold. */
    private void checkSale(ObjectNode holding, BigDecimal nominal, String cls) {
        if ("HTM".equals(cls)) {
            throw ApiException.unprocessable("htm_sale_restricted",
                    "Held-to-maturity positions cannot be sold without tainting the portfolio; reclassify first");
        }
        if (Rows.bool(holding, "is_encumbered")) {
            throw ApiException.unprocessable("holding_encumbered", "The holding is pledged as collateral");
        }
        BigDecimal pendingSales = rows.scalar("""
                select coalesce(sum(nominal), 0) from securities_trade
                where securities_holding_id = ? and buy_sell = 'SELL' and status = 'PENDING'""", BigDecimal.class,
                Rows.uuid(holding, "id")).orElse(BigDecimal.ZERO);
        if (Rows.decOr0(holding, "nominal").subtract(pendingSales).compareTo(nominal) < 0) {
            throw ApiException.unprocessable("insufficient_holding", "Nominal available for sale is "
                    + Rows.decOr0(holding, "nominal").subtract(pendingSales).toPlainString());
        }
    }

    @Transactional
    public ObjectNode settleTrade(UUID id) {
        ObjectNode trade = rows.lock(TRADE, id);
        Support.transition(Lifecycle.TRADE, trade, "SETTLED", "trade");
        LocalDate settle = Rows.date(trade, "settlement_date");
        if (settle.isAfter(rows.today(Rows.uuid(trade, "legal_entity_id")))) {
            throw ApiException.unprocessable("value_date_not_reached", "The trade settles on " + settle);
        }
        ObjectNode holding = rows.lock(HOLDING, Rows.uuid(trade, "securities_holding_id"));
        String ccy = Rows.str(trade, "currency");
        BigDecimal nominal = Rows.dec(trade, "nominal");
        BigDecimal clean = Rows.dec(trade, "clean_price");
        BigDecimal accrued = Rows.dec(trade, "accrued_interest");
        BigDecimal amount = Rows.dec(trade, "settlement_amount");
        BigDecimal consideration = amount.subtract(accrued);
        BigDecimal held = Rows.decOr0(holding, "nominal");
        BigDecimal avg = Rows.dec(holding, "average_price");
        String ref = Rows.str(trade, "deal_ref");
        ObjectNode hv = rows.object();
        ObjectNode tv = rows.object();
        List<Line> lines = new ArrayList<>();
        List<Flow> flows;
        if ("BUY".equals(Rows.str(trade, "buy_sell"))) {
            BigDecimal newNominal = held.add(nominal);
            BigDecimal newAvg = (avg == null || held.signum() == 0) ? clean
                    : held.multiply(avg, MC).add(nominal.multiply(clean, MC), MC).divide(newNominal, MC);
            hv.put("nominal", newNominal.toPlainString());
            hv.put("average_price", newAvg.setScale(10, RoundingMode.HALF_EVEN).toPlainString());
            hv.put("book_value", Currencies.round(newNominal.multiply(newAvg, MC).divide(BigDecimal.valueOf(100), MC), ccy).toPlainString());
            lines.add(Line.debit(ccy, "securities", consideration));
            lines.add(Line.debit(ccy, "interest-receivable", accrued));
            lines.add(Line.credit(ccy, "nostro", amount));
            flows = List.of(Flow.pay("SECURITY_CASH_PAY", amount, ccy, settle));
        } else {
            if (held.compareTo(nominal) < 0) {
                throw ApiException.unprocessable("insufficient_holding", "The holding has only " + held.toPlainString());
            }
            BigDecimal cost = Currencies.round(nominal.multiply(avg, MC).divide(BigDecimal.valueOf(100), MC), ccy);
            BigDecimal pnl = consideration.subtract(cost);
            BigDecimal newNominal = held.subtract(nominal);
            hv.put("nominal", newNominal.toPlainString());
            hv.put("book_value", Currencies.round(newNominal.multiply(avg, MC).divide(BigDecimal.valueOf(100), MC), ccy).toPlainString());
            hv.put("realised_pnl", Rows.decOr0(holding, "realised_pnl").add(pnl).toPlainString());
            tv.put("realised_pnl", pnl.toPlainString());
            lines.add(Line.debit(ccy, "nostro", amount));
            lines.add(Line.credit(ccy, "securities", cost));
            lines.add(Line.credit(ccy, "interest-receivable", accrued));
            lines.add(pnl.signum() >= 0 ? Line.credit(ccy, "realised-pnl", pnl) : Line.debit(ccy, "realised-pnl", pnl.negate()));
            flows = List.of(Flow.receive("SECURITY_CASH_RECEIVE", amount, ccy, settle));
        }
        UUID je = ledger.post(new Ports.Posting("sec-settle:" + id, "STANDARD", "Securities " + Rows.str(trade, "buy_sell")
                + " " + ref, "SECURITIES_TRADE", id, settle, ref, lines));
        settlements.create(DEAL_TYPE, id, Rows.uuid(trade, "treasury_counterparty_id"), null, je, flows);
        rows.update(HOLDING, Rows.uuid(holding, "id"), hv);
        tv.put("status", "SETTLED");
        tv.put("settled_at", rows.now());
        tv.put("journal_entry_id", je.toString());
        ObjectNode out = rows.update(TRADE, id, tv);
        events.publish("treasury.securities_trade.settled", TRADE, out);
        return out;
    }

    @Transactional
    public ObjectNode cancelTrade(UUID id, String reason) {
        Support.required(reason, "reason");
        ObjectNode trade = rows.lock(TRADE, id);
        Support.transition(Lifecycle.TRADE, trade, "CANCELLED", "trade");
        ObjectNode v = rows.object();
        v.put("status", "CANCELLED");
        v.put("cancel_reason", reason);
        ObjectNode out = rows.update(TRADE, id, v);
        limits.release(DEAL_TYPE, id, false);
        events.publish("treasury.securities_trade.cancelled", TRADE, out);
        return out;
    }

    // ------------------------------------------------------------------ holdings

    /** Revalues the holdings of an entity: clean market value at the last price, accrued coupon, unrealised P&L. */
    @Transactional
    public ObjectNode revalue(LocalDate valuationDate, UUID legalEntityId) {
        Support.required(valuationDate, "valuation_date");
        List<ObjectNode> holdings = rows.query("""
                select row_to_json(h)::text from securities_holding h
                where (?::uuid is null or legal_entity_id = ?::uuid) and nominal > 0 order by id""",
                legalEntityId == null ? null : legalEntityId.toString(), legalEntityId == null ? null : legalEntityId.toString());
        int valued = 0;
        int noPrice = 0;
        BigDecimal mvTotal = BigDecimal.ZERO;
        BigDecimal pnlTotal = BigDecimal.ZERO;
        ObjectNode out = rows.object();
        ArrayNode list = out.putArray("holdings");
        for (ObjectNode h : holdings) {
            ObjectNode sec = rows.get(SECURITY, Rows.uuid(h, "treasury_security_id"));
            String ccy = Rows.str(sec, "currency");
            BigDecimal nominal = Rows.dec(h, "nominal");
            ObjectNode v = rows.object();
            v.put("accrued_interest", BondMath.accruedAmount(terms(sec), nominal, valuationDate, ccy).toPlainString());
            v.put("valuation_date", valuationDate.toString());
            BigDecimal price = Rows.dec(sec, "last_price");
            LocalDate priceDate = Rows.date(sec, "last_price_date");
            if (price == null || priceDate.isAfter(valuationDate)) {
                noPrice++;
            } else {
                BigDecimal mv = BondMath.cleanConsideration(nominal, price, ccy);
                BigDecimal pnl = mv.subtract(Rows.decOr0(h, "book_value"));
                v.put("market_value", mv.toPlainString());
                v.put("unrealised_pnl", pnl.toPlainString());
                mvTotal = mvTotal.add(mv);
                pnlTotal = pnlTotal.add(pnl);
                valued++;
            }
            ObjectNode updated = rows.update(HOLDING, Rows.uuid(h, "id"), v);
            events.publish("treasury.securities_holding.revalued", HOLDING, updated);
            ObjectNode s = list.addObject();
            s.put("id", Rows.str(updated, "id"));
            s.put("isin", Rows.str(sec, "isin"));
            s.put("accounting_class", Rows.str(updated, "accounting_class"));
            s.set("market_value", updated.get("market_value"));
            s.set("unrealised_pnl", updated.get("unrealised_pnl"));
            s.set("accrued_interest", updated.get("accrued_interest"));
        }
        out.put("valuation_date", valuationDate.toString());
        out.put("holdings_valued", valued);
        out.put("holdings_without_price", noPrice);
        out.put("total_market_value", mvTotal.toPlainString());
        out.put("total_unrealised_pnl", pnlTotal.toPlainString());
        return out;
    }

    @Transactional
    public ObjectNode encumber(UUID id, Boolean encumbered, String reason) {
        Support.required(encumbered, "is_encumbered");
        ObjectNode h = rows.lock(HOLDING, id);
        if (Rows.bool(h, "is_encumbered") == encumbered) {
            throw ApiException.conflict(encumbered ? "already_encumbered" : "not_encumbered",
                    "The holding is " + (encumbered ? "already pledged" : "not pledged"));
        }
        if (encumbered && Rows.decOr0(h, "nominal").signum() == 0) {
            throw ApiException.unprocessable("empty_holding", "An empty holding cannot be pledged");
        }
        ObjectNode v = rows.object();
        v.put("is_encumbered", encumbered);
        if (reason != null) {
            v.put("encumbrance_reason", reason);
        }
        ObjectNode out = rows.update(HOLDING, id, v);
        events.publish("treasury.securities_holding.encumbrance_changed", HOLDING, out);
        return out;
    }
}
