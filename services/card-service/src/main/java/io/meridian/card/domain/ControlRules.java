package io.meridian.card.domain;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

/** Validation of card controls and spend limits (shape per control type, period limits). */
public final class ControlRules {
    private ControlRules() {}

    public static final Set<String> CHANNELS = Set.of("ECOM", "POS", "ATM", "CONTACTLESS", "MOTO", "RECURRING", "ANY");
    public static final Set<String> DAYS = Set.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");
    private static final Set<String> CONTROL_TYPES = Set.of("MCC_ALLOW", "MCC_DENY", "MERCHANT_ALLOW", "MERCHANT_DENY",
            "COUNTRY_ALLOW", "COUNTRY_DENY", "CHANNEL_ALLOW", "CHANNEL_DENY", "AMOUNT_MAX", "TIME_WINDOW", "CURRENCY_ALLOW");

    public record TimeWindow(List<String> days, String start, String end) {}

    public record ControlSpec(String controlType, String channel, List<String> values, BigDecimal maxAmount,
                              String currency, TimeWindow timeWindow, String timezone, OffsetDateTime validFrom,
                              OffsetDateTime validTo) {}

    public static void validateControl(ControlSpec c) {
        String type = c.controlType();
        if (type == null || !CONTROL_TYPES.contains(type)) {
            throw bad("invalid_control_type", "control_type is required and must be a CardControlType");
        }
        if (c.channel() != null && !CHANNELS.contains(c.channel())) {
            throw bad("invalid_channel", "channel must be a CardChannel");
        }
        List<String> values = c.values() == null ? List.of() : c.values();
        switch (type) {
            case "MCC_ALLOW", "MCC_DENY" -> requireValues(type, values, "\\d{4}", "4-digit MCCs");
            case "MERCHANT_ALLOW", "MERCHANT_DENY" -> requireValues(type, values, "[A-Za-z0-9 _\\-]{1,32}", "merchant ids");
            case "COUNTRY_ALLOW", "COUNTRY_DENY" -> requireValues(type, values, "[A-Z]{2}", "ISO 3166 alpha-2 countries");
            case "CURRENCY_ALLOW" -> requireValues(type, values, "[A-Z]{3}", "ISO 4217 currencies");
            case "CHANNEL_ALLOW", "CHANNEL_DENY" -> {
                if (values.isEmpty() || !CHANNELS.containsAll(values)) {
                    throw bad("invalid_control_values", type + " requires values from CardChannel");
                }
            }
            case "AMOUNT_MAX" -> {
                if (c.maxAmount() == null || c.maxAmount().signum() <= 0 || c.currency() == null) {
                    throw bad("invalid_control_values", "AMOUNT_MAX requires a positive max_amount and a currency");
                }
            }
            case "TIME_WINDOW" -> validateWindow(c.timeWindow(), c.timezone());
            default -> { }
        }
        if (c.maxAmount() != null && c.maxAmount().signum() <= 0) {
            throw bad("invalid_amount", "max_amount must be positive");
        }
        if (c.maxAmount() != null && c.currency() == null) {
            throw bad("currency_required", "currency is required with max_amount");
        }
        if (c.validFrom() != null && c.validTo() != null && !c.validFrom().isBefore(c.validTo())) {
            throw bad("invalid_validity", "valid_from must be before valid_to");
        }
    }

    public static void validateLimit(String period, String channel, BigDecimal maxAmount, Integer maxCount,
                                     String currency) {
        if (period == null) {
            throw bad("invalid_period", "period is required");
        }
        if (channel != null && !CHANNELS.contains(channel)) {
            throw bad("invalid_channel", "channel must be a CardChannel");
        }
        if (maxAmount == null && maxCount == null) {
            throw bad("limit_required", "max_amount and/or max_count is required");
        }
        if (maxAmount != null && maxAmount.signum() <= 0) {
            throw bad("invalid_amount", "max_amount must be positive");
        }
        if (maxCount != null && maxCount <= 0) {
            throw bad("invalid_count", "max_count must be positive");
        }
        if ("PER_TRANSACTION".equals(period) && maxCount != null) {
            throw bad("invalid_count", "a PER_TRANSACTION limit cannot have max_count");
        }
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw bad("currency_required", "currency is required");
        }
    }

    /** Exactly one scope (card or program) per control / limit. */
    public static void validateScope(Object cardId, Object programId) {
        if ((cardId == null) == (programId == null)) {
            throw bad("invalid_scope", "exactly one of card_id or card_program_id is required");
        }
    }

    private static void validateWindow(TimeWindow w, String timezone) {
        if (w == null || w.days() == null || w.days().isEmpty() || !DAYS.containsAll(w.days())) {
            throw bad("invalid_time_window", "TIME_WINDOW requires time_window.days from MON..SUN");
        }
        if (w.start() == null || w.end() == null || !isTime(w.start()) || !isTime(w.end())
                || w.start().compareTo(w.end()) >= 0) {
            throw bad("invalid_time_window", "time_window.start and end must be HH:MM with start before end");
        }
        if (timezone == null) {
            throw bad("invalid_timezone", "TIME_WINDOW requires a timezone");
        }
        try {
            ZoneId.of(timezone);
        } catch (DateTimeException e) {
            throw bad("invalid_timezone", "timezone must be a valid IANA zone id");
        }
    }

    public static boolean isTime(String s) {
        return s != null && s.matches("([01]\\d|2[0-3]):[0-5]\\d");
    }

    private static void requireValues(String type, List<String> values, String regex, String what) {
        if (values.isEmpty()) {
            throw bad("invalid_control_values", type + " requires values (" + what + ")");
        }
        for (String v : values) {
            if (v == null || !v.matches(regex)) {
                throw bad("invalid_control_values", type + " values must be " + what);
            }
        }
    }

    private static ApiException bad(String code, String msg) {
        return ApiException.unprocessable(code, msg);
    }
}
