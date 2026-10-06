package io.meridian.fx.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Margin rule selection. A rule applies when its currency pair matches (exact {@code SELL/BUY} is preferred over
 * {@code * /BUY}, then {@code SELL/*}, then {@code * /*}), its segment and channel are empty or equal, the sell
 * amount is inside its band and today is within its validity. Among the most specific pair matches, the lowest
 * priority wins.
 */
public final class MarginRules {
    private MarginRules() {}

    public record Rule(UUID id, String currencyPair, String segment, String channel, BigDecimal minAmount,
                       BigDecimal maxAmount, int marginBps, BigDecimal minFee, int priority, LocalDate validFrom,
                       LocalDate validTo) {}

    /** Specificity of a pair pattern for sell/buy: 0 exact .. 3 any/any; -1 when it does not match. */
    static int specificity(String pattern, String sell, String buy) {
        if (pattern == null) {
            return -1;
        }
        String[] p = pattern.split("/", -1);
        if (p.length != 2) {
            return -1;
        }
        boolean s = "*".equals(p[0]) || p[0].equals(sell);
        boolean b = "*".equals(p[1]) || p[1].equals(buy);
        if (!s || !b) {
            return -1;
        }
        if (!"*".equals(p[0]) && !"*".equals(p[1])) {
            return 0;
        }
        if ("*".equals(p[0]) && !"*".equals(p[1])) {
            return 1;
        }
        if (!"*".equals(p[0])) {
            return 2;
        }
        return 3;
    }

    public static Optional<Rule> select(List<Rule> rules, String sell, String buy, String segment, String channel,
                                        BigDecimal sellAmount, LocalDate today) {
        return rules.stream()
                .filter(r -> specificity(r.currencyPair(), sell, buy) >= 0)
                .filter(r -> blank(r.segment()) || r.segment().equalsIgnoreCase(segment))
                .filter(r -> blank(r.channel()) || r.channel().equals(channel))
                .filter(r -> sellAmount == null || r.minAmount() == null || sellAmount.compareTo(r.minAmount()) >= 0)
                .filter(r -> sellAmount == null || r.maxAmount() == null || sellAmount.compareTo(r.maxAmount()) <= 0)
                .filter(r -> r.validFrom() == null || !today.isBefore(r.validFrom()))
                .filter(r -> r.validTo() == null || !today.isAfter(r.validTo()))
                .min(Comparator.comparingInt((Rule r) -> specificity(r.currencyPair(), sell, buy))
                        .thenComparingInt(Rule::priority)
                        .thenComparingInt(r -> (blank(r.segment()) ? 1 : 0) + (blank(r.channel()) ? 1 : 0)));
    }

    public static boolean validPair(String pair) {
        if (pair == null || !pair.matches("(\\*|[A-Z]{3})/(\\*|[A-Z]{3})")) {
            return false;
        }
        String[] p = pair.split("/");
        return ("*".equals(p[0]) || FxPricing.isCurrency(p[0])) && ("*".equals(p[1]) || FxPricing.isCurrency(p[1]));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
