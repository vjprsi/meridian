package io.meridian.cardauthorization.domain;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Everything the decision engine needs about one authorisation, as an immutable snapshot: the card, program and
 * product (from card-service's auth-context), active controls and spend limits, current period usage counters,
 * the normalised request and the database clock ({@code now}). No I/O happens inside the engine.
 */
public record AuthContext(OffsetDateTime now, CardSnap card, ProgramSnap program, ProductSnap product,
                          List<Control> controls, List<Limit> limits, Map<String, Usage> usage, Request request) {

    public AuthContext {
        controls = controls == null ? List.of() : List.copyOf(controls);
        limits = limits == null ? List.of() : List.copyOf(limits);
        usage = usage == null ? Map.of() : Map.copyOf(usage);
    }

    /** Usage key of a counter: limit id + period start instant. */
    public static String usageKey(String limitRef, OffsetDateTime periodStart) {
        return limitRef + "|" + periodStart.toInstant();
    }

    public Usage usageOf(Limit l) {
        Usage u = usage.get(usageKey(l.id(), Periods.start(l.period(), now)));
        return u == null ? Usage.NONE : u;
    }

    public record CardSnap(UUID id, String status, int expiryMonth, int expiryYear, boolean pinSet) {}

    public record ProgramSnap(UUID id, String status, String fundingModel, String currency, String country,
                              String fraudPolicy, int fraudDeclineScore, boolean standInEnabled,
                              BigDecimal standInMaxAmount) {}

    public record ProductSnap(String status, String pinPolicy, boolean contactlessEnabled, boolean atmEnabled,
                              boolean ecomEnabled, boolean internationalEnabled) {}

    /** A card or program control; {@code values} upper-cased. Time windows: days + [start, end) in {@code zone}. */
    public record Control(UUID id, String type, String channel, Set<String> values, BigDecimal maxAmount,
                          String currency, Set<DayOfWeek> days, LocalTime start, LocalTime end, ZoneId zone,
                          boolean active, OffsetDateTime validFrom, OffsetDateTime validTo) {

        public boolean appliesAt(OffsetDateTime now, String requestChannel) {
            return active && (validFrom == null || !now.isBefore(validFrom)) && (validTo == null || now.isBefore(validTo))
                    && ("ANY".equals(channel) || channel == null || channel.equals(requestChannel));
        }
    }

    public record Limit(String id, String period, String channel, BigDecimal maxAmount, Integer maxCount,
                        String currency, boolean active) {

        public boolean appliesTo(String requestChannel, String billingCurrency) {
            return active && ("ANY".equals(channel) || channel == null || channel.equals(requestChannel))
                    && (currency == null || currency.equals(billingCurrency));
        }
    }

    public record Usage(BigDecimal amount, int count) {
        public static final Usage NONE = new Usage(BigDecimal.ZERO, 0);
    }

    /**
     * Normalised request. {@code billingAmount} already includes conversion and FX markup; the has* flags say which
     * sensitive inputs (verified by the vault) were present, never their values.
     */
    public record Request(String messageType, String channel, String posEntryMode, BigDecimal transactionAmount,
                          String transactionCurrency, BigDecimal billingAmount, String billingCurrency, String mcc,
                          String merchantId, String merchantCountry, boolean cardPresent, boolean international,
                          String eci, boolean hasCvv, boolean hasExpiry, boolean hasPin, boolean recurring) {}
}
