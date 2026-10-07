package io.meridian.fx.application;

import static io.meridian.fx.infrastructure.Rows.dec;
import static io.meridian.fx.infrastructure.Rows.str;
import static io.meridian.fx.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.fx.config.FxProperties;
import io.meridian.fx.domain.FxPricing;
import io.meridian.fx.domain.MarginRules;
import io.meridian.fx.infrastructure.ReferenceDataClient;
import io.meridian.fx.infrastructure.Rows;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Firm FX quotes (margin rules over the reference-data mid rate, expiry on the database clock), acceptance into an
 * FX deal, cancellation, and remittance corridor pricing (partner routing, fees, delivery time).
 */
@Service
public class QuoteService {
    static final String QUOTE = "fx_quote";
    static final String DEAL = "fx_deal";

    private final Rows rows;
    private final ReferenceDataClient refdata;
    private final Events events;
    private final FxProperties props;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final io.meridian.web.time.BusinessDates businessDates;

    public QuoteService(Rows rows, ReferenceDataClient refdata, Events events, FxProperties props, Clock clock,
                        TransactionTemplate tx, io.meridian.web.time.BusinessDates businessDates) {
        this.businessDates = businessDates;
        this.rows = rows;
        this.refdata = refdata;
        this.events = events;
        this.props = props;
        this.clock = clock;
        this.tx = tx;
    }

    // ------------------------------------------------------------------ quote

    public ObjectNode create(JsonNode req) {
        String sell = str(req, "sell_currency");
        String buy = str(req, "buy_currency");
        requireCurrency("sell_currency", sell);
        requireCurrency("buy_currency", buy);
        if (sell.equals(buy)) {
            throw ApiException.unprocessable("same_currency", "sell_currency and buy_currency must differ");
        }
        BigDecimal sellAmount = positiveOrNull(req, "sell_amount");
        BigDecimal buyAmount = positiveOrNull(req, "buy_amount");
        if ((sellAmount == null) == (buyAmount == null)) {
            throw ApiException.unprocessable("one_side_required", "Give exactly one of sell_amount or buy_amount");
        }
        requirePrecision("sell_amount", sellAmount, sell);
        requirePrecision("buy_amount", buyAmount, buy);
        String segment = str(req, "segment");
        String channel = str(req, "channel");

        BigDecimal mid = refdata.midRate(sell, buy);
        BigDecimal bandAmount = sellAmount != null ? sellAmount : buyAmount.divide(mid, MathContext.DECIMAL128);
        Optional<MarginRules.Rule> rule = MarginRules.select(rules(), sell, buy, segment, channel, bandAmount, today());
        int bps = rule.map(MarginRules.Rule::marginBps).orElse(props.defaultMarginBps());
        BigDecimal fee = rule.map(MarginRules.Rule::minFee).orElse(null);
        FxPricing.Priced p = FxPricing.price(sell, buy, sellAmount, buyAmount, mid, bps, fee);

        return tx.execute(s -> {
            ObjectNode v = rows.object();
            putIfPresent(v, req, "customer_id");
            putIfPresent(v, req, "segment");
            putIfPresent(v, req, "channel");
            v.put("sell_currency", sell);
            v.put("buy_currency", buy);
            fill(v, p, sell);
            rule.ifPresent(r -> v.put("fx_margin_rule_id", r.id().toString()));
            v.put("expires_at", expiry());
            ObjectNode row = rows.insert(QUOTE, v);
            events.publish("payments.fx_quote.created", QUOTE, row);
            return row;
        });
    }

    public ObjectNode cancel(UUID id) {
        return tx.execute(s -> {
            ObjectNode q = rows.lock(QUOTE, id);
            if (!"ACTIVE".equals(str(q, "status"))) {
                throw ApiException.conflict("invalid_transition", "Only an ACTIVE quote can be cancelled (status "
                        + str(q, "status") + ")");
            }
            ObjectNode row = rows.update(QUOTE, id, rows.object().put("status", "CANCELLED"));
            events.publish("payments.fx_quote.cancelled", QUOTE, row);
            return row;
        });
    }

    /** Accepts an ACTIVE, unexpired quote and books the deal. An expired quote is marked EXPIRED (committed). */
    public ObjectNode accept(UUID id, UUID paymentId) {
        Object result = tx.execute(s -> {
            ObjectNode q = rows.lock(QUOTE, id);
            String status = str(q, "status");
            if ("ACCEPTED".equals(status)) {
                throw ApiException.conflict("quote_already_accepted", "The quote was already accepted");
            }
            if (!"ACTIVE".equals(status)) {
                throw ApiException.conflict("quote_not_active", "The quote is " + status);
            }
            boolean expired = rows.scalar("select expires_at <= now() from fx_quote where id = ?", Boolean.class, id)
                    .orElse(true);
            if (expired) {
                rows.update(QUOTE, id, rows.object().put("status", "EXPIRED"));
                return Boolean.FALSE;
            }
            ObjectNode deal = rows.object();
            deal.put("fx_quote_id", id.toString());
            if (paymentId != null) {
                deal.put("payment_id", paymentId.toString());
            }
            deal.put("side", "BUY");
            deal.put("sell_currency", str(q, "sell_currency"));
            deal.put("sell_amount", dec(q, "sell_amount").toPlainString());
            deal.put("buy_currency", str(q, "buy_currency"));
            deal.put("buy_amount", dec(q, "buy_amount").toPlainString());
            deal.put("rate", dec(q, "customer_rate").toPlainString());
            LocalDate today = today();
            deal.put("value_date", (paymentId != null || q.hasNonNull("remittance_corridor_id")
                    ? today : FxPricing.addBusinessDays(today, props.spotDays())).toString());
            ObjectNode row = rows.insert(DEAL, deal);
            rows.update(QUOTE, id, rows.object().put("status", "ACCEPTED"));
            events.publish("payments.fx_deal.booked", DEAL, row);
            return row;
        });
        if (Boolean.FALSE.equals(result)) {
            throw ApiException.conflict("quote_expired", "The quote expired; request a new quote");
        }
        return (ObjectNode) result;
    }

    // ------------------------------------------------------------------ remittance

    public ObjectNode corridorQuote(UUID corridorId, JsonNode req) {
        ObjectNode c = rows.get("remittance_corridor", corridorId);
        if (!"ACTIVE".equals(str(c, "status"))) {
            throw ApiException.conflict("corridor_not_active", "The corridor is " + str(c, "status"));
        }
        String src = str(c, "source_currency");
        String dst = str(c, "destination_currency");
        BigDecimal send = positiveOrNull(req, "send_amount");
        BigDecimal receive = positiveOrNull(req, "receive_amount");
        if ((send == null) == (receive == null)) {
            throw ApiException.unprocessable("one_side_required", "Give exactly one of send_amount or receive_amount");
        }
        requirePrecision("send_amount", send, src);
        requirePrecision("receive_amount", receive, dst);
        ObjectNode partner = partnerFor(c);

        BigDecimal mid = src.equals(dst) ? BigDecimal.ONE : refdata.midRate(src, dst);
        Optional<MarginRules.Rule> rule = Optional.empty();
        int bps;
        if (c.hasNonNull("margin_bps")) {
            bps = c.get("margin_bps").asInt();
        } else {
            BigDecimal band = send != null ? send : receive.divide(mid, MathContext.DECIMAL128);
            rule = MarginRules.select(rules(), src, dst, null, null, band, today());
            bps = rule.map(MarginRules.Rule::marginBps).orElse(props.defaultMarginBps());
        }
        if (src.equals(dst)) {
            bps = 0;
        }
        FxPricing.Priced base = FxPricing.price(src, dst, send, receive, mid, bps, BigDecimal.ZERO);
        BigDecimal sendAmount = base.sellAmount();
        if (sendAmount.compareTo(dec(c, "min_amount")) < 0 || sendAmount.compareTo(dec(c, "max_amount")) > 0) {
            throw ApiException.unprocessable("amount_out_of_corridor_range", "Send amount " + sendAmount.toPlainString()
                    + " is outside " + dec(c, "min_amount").toPlainString() + ".." + dec(c, "max_amount").toPlainString());
        }
        BigDecimal fee = FxPricing.remittanceFee(dec(c, "fixed_fee"), dec(c, "percent_fee"), sendAmount, src);
        FxPricing.Priced p = new FxPricing.Priced(sendAmount, base.buyAmount(), base.midRate(), base.customerRate(),
                bps, fee, sendAmount.add(fee));
        Integer eta = c.hasNonNull("delivery_eta_minutes") ? Integer.valueOf(c.get("delivery_eta_minutes").asInt())
                : partner.hasNonNull("sla_minutes") ? Integer.valueOf(partner.get("sla_minutes").asInt()) : null;
        Optional<MarginRules.Rule> appliedRule = rule;
        return tx.execute(s -> {
            ObjectNode v = rows.object();
            putIfPresent(v, req, "customer_id");
            v.put("sell_currency", src);
            v.put("buy_currency", dst);
            fill(v, p, src);
            appliedRule.ifPresent(r -> v.put("fx_margin_rule_id", r.id().toString()));
            v.put("remittance_corridor_id", corridorId.toString());
            v.put("remittance_partner_id", str(partner, "id"));
            if (eta != null) {
                v.put("delivery_eta_minutes", eta);
            }
            v.put("expires_at", expiry());
            ObjectNode row = rows.insert(QUOTE, v);
            events.publish("payments.fx_quote.created", QUOTE, row);
            return row;
        });
    }

    /** Primary partner when active and able to pay out in the corridor, else the fallback partner. */
    private ObjectNode partnerFor(ObjectNode corridor) {
        String country = str(corridor, "destination_country");
        String method = str(corridor, "payout_method");
        for (String f : List.of("remittance_partner_id", "fallback_partner_id")) {
            UUID pid = uuid(corridor, f);
            if (pid == null) {
                continue;
            }
            Optional<ObjectNode> p = rows.find("remittance_partner", pid);
            if (p.isPresent() && Rows.bool(p.get(), "is_active") && contains(p.get().get("countries"), country)
                    && contains(p.get().get("payout_methods"), method)) {
                return p.get();
            }
        }
        throw ApiException.unprocessable("no_partner_available",
                "No active partner pays out " + method + " in " + country + " for this corridor");
    }

    // ------------------------------------------------------------------ helpers

    private void fill(ObjectNode v, FxPricing.Priced p, String sellCcy) {
        v.put("sell_amount", p.sellAmount().toPlainString());
        v.put("buy_amount", p.buyAmount().toPlainString());
        v.put("mid_rate", p.midRate().toPlainString());
        v.put("customer_rate", p.customerRate().toPlainString());
        v.put("margin_bps", p.marginBps());
        v.put("fee_amount", p.fee().toPlainString());
        v.put("fee_currency", sellCcy);
        v.put("total_debit", p.totalDebit().toPlainString());
        v.put("rate_source", "REFERENCE_DATA");
        v.put("status", "ACTIVE");
    }

    /** Expiry from the database clock, so the SQL expiry check and the stored value use one clock. */
    private String expiry() {
        return rows.scalar("select (now() + make_interval(secs => ?::int))::text", String.class, props.quoteTtlSeconds())
                .orElseThrow();
    }

    List<MarginRules.Rule> rules() {
        return rows.query("select row_to_json(t)::text from fx_margin_rule t").stream().map(r -> new MarginRules.Rule(
                uuid(r, "id"), str(r, "currency_pair"), str(r, "segment"), str(r, "channel"), dec(r, "min_amount"),
                dec(r, "max_amount"), r.path("margin_bps").asInt(), dec(r, "min_fee"), r.path("priority").asInt(100),
                Rows.date(r, "valid_from"), Rows.date(r, "valid_to"))).toList();
    }

    /** The tenant's business date (business-day-service), else the service clock's date. */
    private LocalDate today() {
        return businessDates.today(() -> LocalDate.now(clock));
    }

    private static boolean contains(JsonNode arr, String value) {
        if (arr instanceof ArrayNode a) {
            for (JsonNode n : a) {
                if (value.equalsIgnoreCase(n.asString())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void putIfPresent(ObjectNode v, JsonNode req, String f) {
        if (req.hasNonNull(f)) {
            v.set(f, req.get(f));
        }
    }

    static void requireCurrency(String field, String code) {
        if (!FxPricing.isCurrency(code)) {
            throw ApiException.unprocessable("invalid_currency", field + " must be an ISO 4217 currency code");
        }
    }

    private static BigDecimal positiveOrNull(JsonNode req, String f) {
        BigDecimal v = dec(req, f);
        if (v != null && v.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount", f + " must be positive");
        }
        return v;
    }

    private static void requirePrecision(String field, BigDecimal amount, String ccy) {
        if (amount != null && amount.stripTrailingZeros().scale() > FxPricing.minorUnits(ccy)) {
            throw ApiException.unprocessable("invalid_amount_precision",
                    field + " has more decimals than " + ccy + " allows (" + FxPricing.minorUnits(ccy) + ")");
        }
    }
}
