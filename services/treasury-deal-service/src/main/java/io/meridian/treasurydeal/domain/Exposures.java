package io.meridian.treasurydeal.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Credit exposures a deal places on counterparty limits.
 *
 * <ul>
 *   <li>Money market lending: MONEY_MARKET, COUNTRY and TENOR on the principal; SETTLEMENT on the principal paid on
 *       the value date (released once the start leg settles). Borrowing creates no credit exposure.</li>
 *   <li>FX: SETTLEMENT on each currency we pay (per leg); PRE_SETTLEMENT = largest base notional x Basel CEM add-on
 *       for the residual tenor (+ positive MTM at refresh); FX gross on the near base notional; TENOR on the largest
 *       notional with the tenor to the last value date. NDFs settle net, so no SETTLEMENT on the gross legs.</li>
 *   <li>Securities purchase: ISSUER on the clean consideration against the issuer counterparty.</li>
 * </ul>
 */
public final class Exposures {
    static final MathContext MC = MathContext.DECIMAL128;

    private Exposures() {}

    /** One exposure: limit type, amount and currency, tenor (days) and whether it ends when the start leg settles. */
    public record Exposure(String limitType, BigDecimal amount, String currency, long tenorDays, boolean settlementOnly) {}

    public record FxLeg(String buySell, String baseCurrency, BigDecimal baseAmount, String quoteCurrency,
                        BigDecimal quoteAmount, LocalDate valueDate) {}

    public static List<Exposure> moneyMarket(String dealType, BigDecimal principal, String currency, LocalDate valueDate,
                                             LocalDate maturity) {
        if (!MoneyMarketMath.isLending(dealType)) {
            return List.of();
        }
        long tenor = maturity == null ? 1 : ChronoUnit.DAYS.between(valueDate, maturity);
        return List.of(new Exposure("MONEY_MARKET", principal, currency, tenor, false),
                new Exposure("COUNTRY", principal, currency, tenor, false),
                new Exposure("TENOR", principal, currency, tenor, false),
                new Exposure("SETTLEMENT", principal, currency, 0, true));
    }

    public static List<Exposure> fx(LocalDate tradeDate, List<FxLeg> legs, boolean nonDeliverable) {
        List<Exposure> out = new ArrayList<>();
        Map<String, BigDecimal> pay = new LinkedHashMap<>();
        BigDecimal maxNotional = BigDecimal.ZERO;
        String baseCcy = legs.getFirst().baseCurrency();
        LocalDate last = legs.getFirst().valueDate();
        for (FxLeg l : legs) {
            if ("BUY".equals(l.buySell())) {
                pay.merge(l.quoteCurrency(), l.quoteAmount(), BigDecimal::add);
            } else {
                pay.merge(l.baseCurrency(), l.baseAmount(), BigDecimal::add);
            }
            maxNotional = maxNotional.max(l.baseAmount());
            if (l.valueDate().isAfter(last)) {
                last = l.valueDate();
            }
        }
        long tenor = ChronoUnit.DAYS.between(tradeDate, last);
        if (!nonDeliverable) {
            pay.forEach((ccy, amt) -> out.add(new Exposure("SETTLEMENT", amt, ccy, tenor, false)));
        }
        BigDecimal pse = maxNotional.multiply(FxMath.pfeAddOn(true, tenor), MC);
        if (pse.signum() > 0) {
            out.add(new Exposure("PRE_SETTLEMENT", pse, baseCcy, tenor, false));
        }
        out.add(new Exposure("FX", legs.getFirst().baseAmount(), baseCcy, tenor, false));
        out.add(new Exposure("TENOR", maxNotional, baseCcy, tenor, false));
        return out;
    }

    public static List<Exposure> securityPurchase(BigDecimal cleanConsideration, String currency, LocalDate settle,
                                                  LocalDate maturity) {
        return List.of(new Exposure("ISSUER", cleanConsideration, currency, ChronoUnit.DAYS.between(settle, maturity), false));
    }
}
