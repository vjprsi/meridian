package io.meridian.insights.domain;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Pure matching of merchant clean-name rules and customer category override rules. */
public final class MatchRules {
    private MatchRules() {}

    /** A merchant rule: pattern matched case-insensitively against the raw descriptor. */
    public record MerchantRule(UUID id, String matchType, String pattern, int priority) {
        public boolean matches(String raw) {
            if (raw == null || pattern == null || pattern.isBlank()) {
                return false;
            }
            String d = raw.trim().toUpperCase(Locale.ROOT);
            String p = pattern.trim().toUpperCase(Locale.ROOT);
            return switch (matchType == null ? "CONTAINS" : matchType) {
                case "PREFIX" -> d.startsWith(p);
                case "REGEX" -> Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(raw).find();
                default -> d.contains(p);
            };
        }
    }

    /** A customer override rule; every criterion that is set must match, and at least one must be set. */
    public record OverrideRule(UUID id, String merchantName, String descriptionPattern, BigDecimal minAmount,
                               BigDecimal maxAmount, int priority) {
        public boolean hasCriteria() {
            return notBlank(merchantName) || notBlank(descriptionPattern) || minAmount != null || maxAmount != null;
        }

        public boolean matches(String cleanName, String raw, BigDecimal amount) {
            if (!hasCriteria()) {
                return false;
            }
            if (notBlank(merchantName) && (cleanName == null || !cleanName.trim().equalsIgnoreCase(merchantName.trim()))) {
                return false;
            }
            if (notBlank(descriptionPattern)
                    && (raw == null || !Pattern.compile(descriptionPattern, Pattern.CASE_INSENSITIVE).matcher(raw).find())) {
                return false;
            }
            BigDecimal abs = amount == null ? null : amount.abs();
            if (minAmount != null && (abs == null || abs.compareTo(minAmount) < 0)) {
                return false;
            }
            return maxAmount == null || (abs != null && abs.compareTo(maxAmount) <= 0);
        }
    }

    public static Optional<MerchantRule> firstMerchantRule(List<MerchantRule> rules, String raw) {
        return rules.stream()
                .sorted(Comparator.comparingInt(MerchantRule::priority).thenComparing(MerchantRule::id))
                .filter(r -> r.matches(raw))
                .findFirst();
    }

    public static Optional<OverrideRule> firstOverride(List<OverrideRule> rules, String cleanName, String raw,
                                                       BigDecimal amount) {
        return rules.stream()
                .sorted(Comparator.comparingInt(OverrideRule::priority).thenComparing(OverrideRule::id))
                .filter(r -> r.matches(cleanName, raw, amount))
                .findFirst();
    }

    /** Null when the expression compiles, else the compiler's message. */
    public static String regexError(String expression) {
        try {
            Pattern.compile(expression);
            return null;
        } catch (PatternSyntaxException e) {
            return e.getDescription();
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
