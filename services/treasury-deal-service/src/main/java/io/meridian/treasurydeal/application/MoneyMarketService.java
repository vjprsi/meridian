package io.meridian.treasurydeal.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.treasurydeal.application.Ports.Line;
import io.meridian.treasurydeal.application.SettlementService.Flow;
import io.meridian.treasurydeal.domain.Confirmations;
import io.meridian.treasurydeal.domain.Exposures;
import io.meridian.treasurydeal.domain.Lifecycle;
import io.meridian.treasurydeal.domain.MoneyMarketMath;
import io.meridian.treasurydeal.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Money market deals: capture with limit reservation, four-eyes verification, confirmation, start-leg settlement,
 * daily accrual, call, maturity (repayment of principal and interest) and rollover, each posted to the ledger:
 * <pre>
 *   settle   lending: DR mm-placements / CR nostro        borrowing: DR nostro / CR mm-borrowings
 *   accrue   lending: DR interest-receivable / CR income  borrowing: DR interest-expense / CR interest-payable
 *   mature   lending: DR nostro (P+I) / CR mm-placements P, CR interest-receivable accrued, CR income rest
 * </pre>
 */
@Service
public class MoneyMarketService {
    static final String TABLE = "money_market_deal";
    static final String DEAL_TYPE = "MONEY_MARKET";
    static final Set<String> REPO = Set.of("REPO", "REVERSE_REPO");
    static final Set<String> CALL = Set.of("CALL_PLACEMENT", "CALL_BORROWING");

    private final Rows rows;
    private final Events events;
    private final CounterpartyService counterparties;
    private final LimitService limits;
    private final SettlementService settlements;
    private final Ports.Ledger ledger;
    private final io.meridian.treasurydeal.config.TreasuryProperties props;
    private final ConfirmationGate gate;

    public MoneyMarketService(Rows rows, Events events, CounterpartyService counterparties, LimitService limits,
                              SettlementService settlements, Ports.Ledger ledger,
                              io.meridian.treasurydeal.config.TreasuryProperties props, ConfirmationGate gate) {
        this.rows = rows;
        this.events = events;
        this.counterparties = counterparties;
        this.limits = limits;
        this.settlements = settlements;
        this.ledger = ledger;
        this.props = props;
        this.gate = gate;
    }

    // ------------------------------------------------------------------ capture / amend

    @Transactional
    public ObjectNode capture(JsonNode req) {
        ObjectNode v = rows.columns(TABLE, req);
        ObjectNode cp = counterparties.requireActive(Rows.uuid(v, "treasury_counterparty_id"));
        normalise(v);
        validate(v, cp, rows.today(Rows.uuid(v, "legal_entity_id")));
        UUID id = Ids.newId();
        v.put("id", id.toString());
        v.put("deal_ref", Support.dealRef("MM", Rows.date(v, "trade_date"), id));
        v.put("status", "CAPTURED");
        computeInterest(v);
        ObjectNode deal = rows.insert(TABLE, v);
        limits.reserve(DEAL_TYPE, id, Rows.uuid(cp, "id"), exposures(deal), Rows.date(deal, "trade_date"));
        events.publish("treasury.money_market_deal.captured", TABLE, deal);
        return deal;
    }

    /** PATCH while CAPTURED: re-validates, recomputes interest and re-reserves limits. */
    @Transactional
    public ObjectNode amend(UUID id, int expectedVersion, JsonNode req) {
        ObjectNode deal = rows.lock(TABLE, id);
        if (Rows.integer(deal, "row_version") != expectedVersion) {
            throw ApiException.conflict("version_conflict", "If-Match does not match the current version; reload and retry");
        }
        if (!"CAPTURED".equals(Rows.str(deal, "status"))) {
            throw ApiException.conflict("deal_not_amendable", "Only CAPTURED deals can be amended; cancel and rebook instead");
        }
        ObjectNode merged = deal.deepCopy();
        ObjectNode changes = rows.columns(TABLE, req);
        merged.setAll(changes);
        ObjectNode cp = counterparties.requireActive(Rows.uuid(merged, "treasury_counterparty_id"));
        normalise(merged);
        validate(merged, cp, rows.today(Rows.uuid(merged, "legal_entity_id")));
        computeInterest(merged);
        changes.setAll(pick(merged, "is_call", "interest_amount", "maturity_amount"));
        ObjectNode out = rows.update(TABLE, id, changes);
        limits.release(DEAL_TYPE, id, false);
        limits.reserve(DEAL_TYPE, id, Rows.uuid(cp, "id"), exposures(out), Rows.date(out, "trade_date"));
        return out;
    }

    private static ObjectNode pick(ObjectNode from, String... fields) {
        ObjectNode o = from.objectNode();
        for (String f : fields) {
            o.set(f, from.get(f) == null ? from.nullNode() : from.get(f));
        }
        return o;
    }

    private static void normalise(ObjectNode v) {
        if (CALL.contains(Rows.str(v, "deal_type"))) {
            v.put("is_call", true);
        }
    }

    private static void validate(ObjectNode v, JsonNode cp, LocalDate today) {
        String type = Rows.str(v, "deal_type");
        Support.required(type, "deal_type");
        String ccy = Rows.str(v, "currency");
        Support.currency(ccy, "currency");
        BigDecimal principal = Rows.dec(v, "principal");
        Support.positive(principal, "principal");
        Support.minorUnits(principal, ccy, "principal");
        LocalDate trade = Rows.date(v, "trade_date");
        LocalDate value = Rows.date(v, "value_date");
        LocalDate maturity = Rows.date(v, "maturity_date");
        Support.required(trade, "trade_date");
        Support.required(value, "value_date");
        if (trade.isAfter(today)) {
            throw ApiException.unprocessable("invalid_dates", "trade_date cannot be in the future");
        }
        Support.ordered(trade, value, "value_date must not be before trade_date");
        boolean call = Rows.bool(v, "is_call");
        if (!call && maturity == null) {
            throw ApiException.unprocessable("missing_field", "maturity_date is required for term deals");
        }
        if (maturity != null && !maturity.isAfter(value)) {
            throw ApiException.unprocessable("invalid_dates", "maturity_date must be after value_date");
        }
        if (call && (Rows.integer(v, "notice_days") == null || Rows.integer(v, "notice_days") < 0)) {
            throw ApiException.unprocessable("missing_field", "notice_days (>= 0) is required for call deals");
        }
        BigDecimal rate = Rows.dec(v, "interest_rate");
        Support.required(rate, "interest_rate");
        if (rate.compareTo(new BigDecimal("-0.05")) < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw ApiException.unprocessable("invalid_rate", "interest_rate must be a decimal annual rate between -5% and 100%");
        }
        if ("FLOATING".equals(Rows.str(v, "rate_type")) && Rows.str(v, "rate_index") == null) {
            throw ApiException.unprocessable("missing_field", "rate_index is required for floating-rate deals");
        }
        if (REPO.contains(type) && !Rows.bool(cp, "gmra_signed")) {
            throw ApiException.unprocessable("gmra_required", "Repos need a GMRA signed with the counterparty");
        }
        if (REPO.contains(type) && Rows.str(v, "collateral_description") == null) {
            throw ApiException.unprocessable("missing_field", "collateral_description is required for repos");
        }
    }

    private static void computeInterest(ObjectNode v) {
        LocalDate maturity = Rows.date(v, "maturity_date");
        if (maturity == null) {
            v.putNull("interest_amount");
            v.putNull("maturity_amount");
            return;
        }
        String ccy = Rows.str(v, "currency");
        BigDecimal principal = Rows.dec(v, "principal");
        BigDecimal interest = MoneyMarketMath.interest(principal, Rows.dec(v, "interest_rate"),
                MoneyMarketMath.dayCount(Rows.str(v, "day_count")), Rows.date(v, "value_date"), maturity, ccy);
        v.put("interest_amount", interest.toPlainString());
        v.put("maturity_amount", principal.add(interest).toPlainString());
    }

    static List<Exposures.Exposure> exposures(JsonNode deal) {
        LocalDate maturity = Rows.date(deal, "maturity_date");
        return Exposures.moneyMarket(Rows.str(deal, "deal_type"), Rows.dec(deal, "principal"), Rows.str(deal, "currency"),
                Rows.date(deal, "value_date"), maturity);
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
        ObjectNode out = rows.update(TABLE, id, v);
        events.publish("treasury.money_market_deal.verified", TABLE, out);
        return out;
    }

    /** Confirms a verified deal: needs a MATCHED inbound confirmation, or a manual reference (recorded as MANUAL). */
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
        ObjectNode out = rows.update(TABLE, id, v);
        events.publish("treasury.money_market_deal.confirmed", TABLE, out);
        return out;
    }

    /** Settles the start leg on or after the value date: settlement instruction, ledger posting, SETTLEMENT limit release. */
    @Transactional
    public ObjectNode settle(UUID id) {
        ObjectNode deal = rows.lock(TABLE, id);
        Support.transition(Lifecycle.DEAL, deal, "SETTLED", "deal");
        LocalDate valueDate = Rows.date(deal, "value_date");
        if (valueDate.isAfter(rows.today(Rows.uuid(deal, "legal_entity_id")))) {
            throw ApiException.unprocessable("value_date_not_reached", "The deal settles on " + valueDate);
        }
        ObjectNode v = rows.object();
        if (Rows.uuid(deal, "rolled_from_deal_id") == null) {
            boolean lend = MoneyMarketMath.isLending(Rows.str(deal, "deal_type"));
            String ccy = Rows.str(deal, "currency");
            BigDecimal p = Rows.dec(deal, "principal");
            UUID je = ledger.post(new Ports.Posting("mm-settle:" + id, "STANDARD",
                    "MM " + Rows.str(deal, "deal_type") + " " + Rows.str(deal, "deal_ref") + " start", "MONEY_MARKET_DEAL", id,
                    valueDate, Rows.str(deal, "deal_ref"), lend
                    ? List.of(Line.debit(ccy, "mm-placements", p), Line.credit(ccy, "nostro", p))
                    : List.of(Line.debit(ccy, "nostro", p), Line.credit(ccy, "mm-borrowings", p))));
            settlements.create(DEAL_TYPE, id, Rows.uuid(deal, "treasury_counterparty_id"), Rows.uuid(deal, "nostro_account_id"),
                    je, List.of(lend ? Flow.pay("PRINCIPAL_OUT", p, ccy, valueDate) : Flow.receive("PRINCIPAL_IN", p, ccy, valueDate)));
            v.put("settlement_journal_entry_id", je.toString());
            UUID carrying = props.account(ccy, lend ? "mm-placements" : "mm-borrowings");
            if (carrying != null) {
                v.put("ledger_account_id", carrying.toString());
            }
        }
        v.put("status", "SETTLED");
        v.put("settled_at", rows.now());
        ObjectNode out = rows.update(TABLE, id, v);
        limits.release(DEAL_TYPE, id, true);
        events.publish("treasury.money_market_deal.settled", TABLE, out);
        return out;
    }

    /** Recalculates the accrued interest through {@code asOf} and posts the change (idempotent per deal and date). */
    @Transactional
    public ObjectNode accrue(UUID id, LocalDate asOf) {
        Support.required(asOf, "as_of");
        ObjectNode deal = rows.lock(TABLE, id);
        if (!"SETTLED".equals(Rows.str(deal, "status"))) {
            throw ApiException.conflict("invalid_transition", "Only SETTLED deals accrue interest");
        }
        LocalDate valueDate = Rows.date(deal, "value_date");
        if (asOf.isBefore(valueDate)) {
            throw ApiException.unprocessable("invalid_dates", "as_of is before the value date");
        }
        String ccy = Rows.str(deal, "currency");
        BigDecimal accrued = MoneyMarketMath.accruedThrough(Rows.dec(deal, "principal"), Rows.dec(deal, "interest_rate"),
                MoneyMarketMath.dayCount(Rows.str(deal, "day_count")), valueDate, Rows.date(deal, "maturity_date"), asOf, ccy);
        BigDecimal delta = accrued.subtract(Rows.decOr0(deal, "accrued_interest"));
        if (delta.signum() != 0) {
            boolean lend = MoneyMarketMath.isLending(Rows.str(deal, "deal_type"));
            BigDecimal a = delta.abs();
            List<Line> lines = lend
                    ? List.of(new Line(ccy, "interest-receivable", delta.signum() > 0 ? "DEBIT" : "CREDIT", a, null),
                              new Line(ccy, "interest-income", delta.signum() > 0 ? "CREDIT" : "DEBIT", a, null))
                    : List.of(new Line(ccy, "interest-expense", delta.signum() > 0 ? "DEBIT" : "CREDIT", a, null),
                              new Line(ccy, "interest-payable", delta.signum() > 0 ? "CREDIT" : "DEBIT", a, null));
            ledger.post(new Ports.Posting("mm-accrual:" + id + ":" + asOf + ":" + accrued.toPlainString(), "ACCRUAL",
                    "MM interest accrual " + Rows.str(deal, "deal_ref") + " through " + asOf, "MONEY_MARKET_DEAL", id,
                    asOf, Rows.str(deal, "deal_ref"), lines));
        }
        ObjectNode v = rows.object();
        v.put("accrued_interest", accrued.toPlainString());
        v.put("accrued_through", asOf.toString());
        return rows.update(TABLE, id, v);
    }

    /** Calls an open-ended deal: maturity = call date + notice days; interest to maturity is fixed. */
    @Transactional
    public ObjectNode call(UUID id, LocalDate callDate) {
        Support.required(callDate, "call_date");
        ObjectNode deal = rows.lock(TABLE, id);
        if (!Rows.bool(deal, "is_call")) {
            throw ApiException.unprocessable("not_a_call_deal", "Only call deals can be called");
        }
        if (Rows.date(deal, "maturity_date") != null) {
            throw ApiException.conflict("already_called", "The deal has already been called (maturity " + Rows.str(deal, "maturity_date") + ")");
        }
        if (!Set.of("CONFIRMED", "SETTLED", "VERIFIED", "CAPTURED").contains(Rows.str(deal, "status"))) {
            throw ApiException.conflict("invalid_transition", "A " + Rows.str(deal, "status") + " deal cannot be called");
        }
        LocalDate maturity = callDate.plusDays(Rows.integer(deal, "notice_days"));
        if (!maturity.isAfter(Rows.date(deal, "value_date"))) {
            throw ApiException.unprocessable("invalid_dates", "call_date plus notice must fall after the value date");
        }
        ObjectNode merged = deal.deepCopy();
        merged.put("maturity_date", maturity.toString());
        computeInterest(merged);
        ObjectNode out = rows.update(TABLE, id, pick(merged, "maturity_date", "interest_amount", "maturity_amount"));
        events.publish("treasury.money_market_deal.called", TABLE, out);
        return out;
    }

    /** Repays principal and interest on the maturity date (or later, e.g. the end-of-day run for that date). */
    @Transactional
    public ObjectNode mature(UUID id, LocalDate asOf) {
        ObjectNode deal = rows.lock(TABLE, id);
        Support.transition(Lifecycle.DEAL, deal, "MATURED", "deal");
        LocalDate maturity = Rows.date(deal, "maturity_date");
        if (maturity == null) {
            throw ApiException.unprocessable("not_called", "Call the deal before processing its maturity");
        }
        LocalDate ref = asOf == null ? rows.today(Rows.uuid(deal, "legal_entity_id")) : asOf;
        if (maturity.isAfter(ref)) {
            throw ApiException.unprocessable("not_due", "The deal matures on " + maturity);
        }
        boolean lend = MoneyMarketMath.isLending(Rows.str(deal, "deal_type"));
        String ccy = Rows.str(deal, "currency");
        BigDecimal p = Rows.dec(deal, "principal");
        BigDecimal interest = Rows.dec(deal, "interest_amount");
        BigDecimal accrued = Rows.decOr0(deal, "accrued_interest").min(interest);
        BigDecimal rest = interest.subtract(accrued);
        List<Line> lines = new ArrayList<>();
        if (lend) {
            lines.add(Line.debit(ccy, "nostro", p.add(interest)));
            lines.add(Line.credit(ccy, "mm-placements", p));
            lines.add(Line.credit(ccy, "interest-receivable", accrued));
            lines.add(Line.credit(ccy, "interest-income", rest));
        } else {
            lines.add(Line.debit(ccy, "mm-borrowings", p));
            lines.add(Line.debit(ccy, "interest-payable", accrued));
            lines.add(Line.debit(ccy, "interest-expense", rest));
            lines.add(Line.credit(ccy, "nostro", p.add(interest)));
        }
        UUID je = ledger.post(new Ports.Posting("mm-mature:" + id, "INTEREST",
                "MM " + Rows.str(deal, "deal_ref") + " maturity", "MONEY_MARKET_DEAL", id, maturity, Rows.str(deal, "deal_ref"), lines));
        settlements.create(DEAL_TYPE, id, Rows.uuid(deal, "treasury_counterparty_id"), Rows.uuid(deal, "nostro_account_id"), je,
                lend ? List.of(Flow.receive("PRINCIPAL_IN", p, ccy, maturity), Flow.receive("INTEREST_IN", interest, ccy, maturity))
                     : List.of(Flow.pay("PRINCIPAL_OUT", p, ccy, maturity), Flow.pay("INTEREST_OUT", interest, ccy, maturity)));
        ObjectNode v = rows.object();
        v.put("status", "MATURED");
        v.put("matured_at", rows.now());
        v.put("accrued_interest", interest.toPlainString());
        v.put("accrued_through", maturity.minusDays(1).toString());
        v.put("maturity_journal_entry_id", je.toString());
        ObjectNode out = rows.update(TABLE, id, v);
        limits.release(DEAL_TYPE, id, false);
        events.publish("treasury.money_market_deal.matured", TABLE, out);
        return out;
    }

    /**
     * Rolls a deal due today into a new deal starting on the old maturity date: PRINCIPAL_ONLY pays the interest
     * and rolls the principal; PRINCIPAL_AND_INTEREST capitalises the interest into the new principal. The new deal
     * starts CAPTURED (verification and confirmation again) and its settlement moves no cash.
     */
    @Transactional
    public ObjectNode rollover(UUID id, BigDecimal newRate, LocalDate newMaturity, String rolloverType, LocalDate asOf) {
        Support.required(newRate, "new_rate");
        Support.required(rolloverType, "rollover_type");
        if ("NONE".equals(rolloverType)) {
            throw ApiException.unprocessable("invalid_rollover_type", "rollover_type must be PRINCIPAL_ONLY or PRINCIPAL_AND_INTEREST");
        }
        ObjectNode deal = rows.lock(TABLE, id);
        Support.transition(Lifecycle.DEAL, deal, "MATURED", "deal");
        LocalDate maturity = Rows.date(deal, "maturity_date");
        LocalDate ref = asOf == null ? rows.today(Rows.uuid(deal, "legal_entity_id")) : asOf;
        if (maturity == null || maturity.isAfter(ref)) {
            throw ApiException.unprocessable("not_due", "Only deals at maturity can be rolled over");
        }
        String ccy = Rows.str(deal, "currency");
        boolean lend = MoneyMarketMath.isLending(Rows.str(deal, "deal_type"));
        BigDecimal p = Rows.dec(deal, "principal");
        BigDecimal interest = Rows.dec(deal, "interest_amount");
        BigDecimal accrued = Rows.decOr0(deal, "accrued_interest").min(interest);
        BigDecimal rest = interest.subtract(accrued);
        boolean capitalise = "PRINCIPAL_AND_INTEREST".equals(rolloverType);
        String cashRole = capitalise ? (lend ? "mm-placements" : "mm-borrowings") : "nostro";
        List<Line> lines = lend
                ? List.of(Line.debit(ccy, cashRole, interest), Line.credit(ccy, "interest-receivable", accrued),
                          Line.credit(ccy, "interest-income", rest))
                : List.of(Line.debit(ccy, "interest-payable", accrued), Line.debit(ccy, "interest-expense", rest),
                          Line.credit(ccy, cashRole, interest));
        UUID je = ledger.post(new Ports.Posting("mm-rollover:" + id, "INTEREST", "MM " + Rows.str(deal, "deal_ref")
                + " rollover (" + rolloverType + ")", "MONEY_MARKET_DEAL", id, maturity, Rows.str(deal, "deal_ref"), lines));
        if (!capitalise) {
            settlements.create(DEAL_TYPE, id, Rows.uuid(deal, "treasury_counterparty_id"), Rows.uuid(deal, "nostro_account_id"),
                    je, List.of(lend ? Flow.receive("INTEREST_IN", interest, ccy, maturity)
                                     : Flow.pay("INTEREST_OUT", interest, ccy, maturity)));
        }
        long tenor = MoneyMarketMath.tenorDays(Rows.date(deal, "value_date"), maturity);
        ObjectNode n = rows.object();
        UUID newId = Ids.newId();
        for (String f : new String[] {"legal_entity_id", "treasury_counterparty_id", "deal_type", "currency", "is_call",
                "notice_days", "rate_type", "rate_index", "spread", "day_count", "collateral_description", "dealer_id",
                "nostro_account_id"}) {
            if (deal.hasNonNull(f)) {
                n.set(f, deal.get(f));
            }
        }
        n.put("id", newId.toString());
        n.put("principal", (capitalise ? p.add(interest) : p).toPlainString());
        n.put("interest_rate", newRate.toPlainString());
        LocalDate today = rows.today(Rows.uuid(deal, "legal_entity_id"));
        n.put("trade_date", today.isBefore(maturity) ? today.toString() : maturity.toString());
        n.put("value_date", maturity.toString());
        if (!Rows.bool(deal, "is_call")) {
            n.put("maturity_date", (newMaturity == null ? maturity.plusDays(tenor) : newMaturity).toString());
        } else if (newMaturity != null) {
            n.put("maturity_date", newMaturity.toString());
        }
        n.put("rollover_type", "NONE");
        n.put("rolled_from_deal_id", id.toString());
        n.put("status", "CAPTURED");
        n.put("deal_ref", Support.dealRef("MM", maturity, newId));
        validate(n, counterparties.requireActive(Rows.uuid(deal, "treasury_counterparty_id")), maturity);
        computeInterest(n);
        ObjectNode fresh = rows.insert(TABLE, n);
        ObjectNode v = rows.object();
        v.put("status", "MATURED");
        v.put("matured_at", rows.now());
        v.put("rollover_type", rolloverType);
        v.put("accrued_interest", interest.toPlainString());
        v.put("rolled_into_deal_id", newId.toString());
        v.put("maturity_journal_entry_id", je.toString());
        ObjectNode old = rows.update(TABLE, id, v);
        limits.release(DEAL_TYPE, id, false);
        limits.reserve(DEAL_TYPE, newId, Rows.uuid(deal, "treasury_counterparty_id"), exposures(fresh).stream()
                .filter(e -> !e.settlementOnly()).toList(), maturity);
        events.publish("treasury.money_market_deal.rolled_over", TABLE, old);
        events.publish("treasury.money_market_deal.captured", TABLE, fresh);
        return fresh;
    }

    @Transactional
    public ObjectNode cancel(UUID id, String reason) {
        Support.required(reason, "reason");
        ObjectNode deal = rows.lock(TABLE, id);
        Support.transition(Lifecycle.DEAL, deal, "CANCELLED", "deal");
        ObjectNode v = rows.object();
        v.put("status", "CANCELLED");
        v.put("cancel_reason", reason);
        ObjectNode out = rows.update(TABLE, id, v);
        limits.release(DEAL_TYPE, id, false);
        settlements.cancelOpen(id);
        events.publish("treasury.money_market_deal.cancelled", TABLE, out);
        return out;
    }

    // ------------------------------------------------------------------ confirmation text

    public Confirmations.MoneyMarket confirmation(JsonNode deal) {
        JsonNode cp = rows.get(CounterpartyService.TABLE, Rows.uuid(deal, "treasury_counterparty_id"));
        return new Confirmations.MoneyMarket(Rows.str(deal, "deal_ref"), props.ourBic(), Rows.str(cp, "bic"),
                MoneyMarketMath.isLending(Rows.str(deal, "deal_type")), Rows.date(deal, "trade_date"),
                Rows.date(deal, "value_date"), Rows.date(deal, "maturity_date"), Rows.str(deal, "currency"),
                Rows.dec(deal, "principal"), Rows.dec(deal, "interest_amount"), Rows.dec(deal, "interest_rate"),
                Rows.str(deal, "day_count"));
    }

    @Transactional(readOnly = true)
    public String confirmationMessage(UUID id) {
        return Confirmations.mt320(confirmation(rows.get(TABLE, id)));
    }
}
