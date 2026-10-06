package io.meridian.liquidity.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * One expected cash movement: an inflow or outflow of {@code amount} (positive) in {@code currency} on
 * {@code valueDate}, classified by a LiquidityFlowCategory.
 */
public record CashFlow(LocalDate valueDate, String currency, boolean inflow, BigDecimal amount, String category,
                       UUID nostroAccountId, String flowType) {

    /** LiquidityFlowCategory values (spec enum). */
    public static final List<String> CATEGORIES = List.of("DEPOSITS", "LOANS", "PAYMENTS", "CARDS", "WALLETS",
            "TREASURY_DEALS", "SECURITIES", "FEES", "OTHER");

    /** Maps a free-text category onto LiquidityFlowCategory (unknown values become OTHER). */
    public static String category(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String v = value.trim().toUpperCase(Locale.ROOT);
        return CATEGORIES.contains(v) ? v : "OTHER";
    }

    public static boolean isCategory(String value) {
        return value != null && CATEGORIES.contains(value);
    }
}
