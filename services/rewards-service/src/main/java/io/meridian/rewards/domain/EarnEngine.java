package io.meridian.rewards.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Pure earn-rule engine: decides which rules of a program match a qualifying transaction and what each awards.
 *
 * <p>Matching: trigger, MCC include / exclude, merchant, product, channel, merchant country, minimum amount, validity
 * period, ISO day-of-week and a daily HH:mm window (may wrap past midnight), all in the rule's time zone. Selection:
 * rules are taken in priority order (lower first, then id); when the highest-priority matching rule is not stackable
 * it is applied alone, otherwise every stackable matching rule is applied (non-stackable ones are skipped).
 *
 * <p>Awards: points = floor(amount x points_per_unit x multiplier x tier multiplier) + fixed; cashback =
 * amount x cashback_rate x multiplier (2 dp, HALF_EVEN) + fixed. POINTS and MILES programs award points, CASHBACK
 * programs cash, HYBRID programs whatever the rule defines (a fixed award is cash when the rule has a cashback rate).
 * A cap limits the award per customer and cap period; the caller supplies what was already awarded in the window.
 */
public final class EarnEngine {
    private EarnEngine() {}

    public record Rule(UUID id, String name, String trigger, int priority, boolean stackable, List<String> mccInclude,
                       List<String> mccExclude, List<String> merchantIds, List<String> productIds, List<String> channels,
                       List<String> countries, BigDecimal minAmount, BigDecimal pointsPerUnit, BigDecimal cashbackRate,
                       BigDecimal fixedAward, BigDecimal multiplier, BigDecimal capAmount, String capPeriod,
                       List<Integer> daysOfWeek, String timeFrom, String timeTo, ZoneId zone, OffsetDateTime validFrom,
                       OffsetDateTime validTo) {
        public Rule {
            mccInclude = nz(mccInclude);
            mccExclude = nz(mccExclude);
            merchantIds = nz(merchantIds);
            productIds = nz(productIds);
            channels = nz(channels);
            countries = nz(countries);
            daysOfWeek = daysOfWeek == null ? List.of() : daysOfWeek;
            multiplier = multiplier == null ? BigDecimal.ONE : multiplier;
            zone = zone == null ? ZoneId.of("UTC") : zone;
        }

        /** True when a cap (other than TRANSACTION) is accumulated across transactions. */
        public boolean periodCap() {
            return capAmount != null && capPeriod != null && !"TRANSACTION".equals(capPeriod);
        }

        /** True when the rule awards cash in a program of the given type. */
        public boolean awardsCash(String programType) {
            return "CASHBACK".equals(programType) || ("HYBRID".equals(programType) && cashbackRate != null);
        }

        /** True when the rule awards points in a program of the given type. */
        public boolean awardsPoints(String programType) {
            return "POINTS".equals(programType) || "MILES".equals(programType)
                    || ("HYBRID".equals(programType) && (pointsPerUnit != null || cashbackRate == null));
        }
    }

    public record Txn(String trigger, BigDecimal amount, String mcc, String merchantId, String country, String channel,
                      String productId, OffsetDateTime at) {}

    /** Award already given by a rule to the customer in [from, to): points and cash. */
    public interface CapUsage {
        Used used(Rule rule, Instant from, Instant to);
    }

    public record Used(long points, BigDecimal cash) {
        public static final Used NONE = new Used(0, BigDecimal.ZERO);
    }

    public record Award(UUID ruleId, String ruleName, long points, BigDecimal cashback, boolean capped) {}

    public record Evaluation(String ruleId, String ruleName, boolean matched, String reason) {}

    public record Result(List<Award> awards, List<Evaluation> evaluations) {
        public long points() {
            return awards.stream().mapToLong(Award::points).sum();
        }

        public BigDecimal cashback() {
            return awards.stream().map(Award::cashback).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    public static Result evaluate(String programType, List<Rule> rules, Txn txn, BigDecimal tierMultiplier, CapUsage usage) {
        BigDecimal tierMult = tierMultiplier == null ? BigDecimal.ONE : tierMultiplier;
        List<Rule> ordered = new ArrayList<>(rules);
        ordered.sort(Comparator.comparingInt(Rule::priority).thenComparing(r -> r.id().toString()));
        List<Evaluation> evals = new ArrayList<>();
        List<Rule> matched = new ArrayList<>();
        for (Rule r : ordered) {
            String why = mismatch(r, txn);
            evals.add(new Evaluation(r.id().toString(), r.name(), why == null, why == null ? "matched" : why));
            if (why == null) {
                matched.add(r);
            }
        }
        List<Rule> applied = new ArrayList<>();
        if (!matched.isEmpty() && !matched.getFirst().stackable()) {
            applied.add(matched.getFirst());
        } else {
            matched.stream().filter(Rule::stackable).forEach(applied::add);
        }
        List<Award> awards = new ArrayList<>();
        for (Rule r : applied) {
            Award a = award(programType, r, txn, tierMult, usage);
            if (a.points() > 0 || a.cashback().signum() > 0) {
                awards.add(a);
            }
        }
        return new Result(awards, evals);
    }

    /** Null when the rule matches the transaction, else the first reason it does not. */
    public static String mismatch(Rule r, Txn t) {
        if (!r.trigger().equals(t.trigger())) {
            return "trigger";
        }
        if (r.validFrom() != null && t.at().isBefore(r.validFrom())) {
            return "not_yet_valid";
        }
        if (r.validTo() != null && !t.at().isBefore(r.validTo())) {
            return "expired";
        }
        if (r.minAmount() != null && t.amount().compareTo(r.minAmount()) < 0) {
            return "below_min_amount";
        }
        if (!r.mccInclude().isEmpty() && (t.mcc() == null || !r.mccInclude().contains(t.mcc()))) {
            return "mcc_not_included";
        }
        if (t.mcc() != null && r.mccExclude().contains(t.mcc())) {
            return "mcc_excluded";
        }
        if (!r.merchantIds().isEmpty() && (t.merchantId() == null || !r.merchantIds().contains(t.merchantId()))) {
            return "merchant";
        }
        if (!r.productIds().isEmpty() && (t.productId() == null || !r.productIds().contains(t.productId()))) {
            return "product";
        }
        if (!r.channels().isEmpty() && (t.channel() == null || !r.channels().contains(t.channel()))) {
            return "channel";
        }
        if (!r.countries().isEmpty() && (t.country() == null || !r.countries().contains(t.country()))) {
            return "country";
        }
        ZonedDateTime local = t.at().atZoneSameInstant(r.zone());
        if (!r.daysOfWeek().isEmpty() && !r.daysOfWeek().contains(local.getDayOfWeek().getValue())) {
            return "day_of_week";
        }
        if (!inWindow(local.toLocalTime(), r.timeFrom(), r.timeTo())) {
            return "time_window";
        }
        return null;
    }

    /** [from, to) daily window; wraps past midnight when from > to; no window when either bound is empty or equal. */
    public static boolean inWindow(LocalTime t, String from, String to) {
        if (from == null || to == null || from.isBlank() || to.isBlank()) {
            return true;
        }
        LocalTime f = LocalTime.parse(from);
        LocalTime e = LocalTime.parse(to);
        if (f.equals(e)) {
            return true;
        }
        if (f.isBefore(e)) {
            return !t.isBefore(f) && t.isBefore(e);
        }
        return !t.isBefore(f) || t.isBefore(e);
    }

    static Award award(String programType, Rule r, Txn t, BigDecimal tierMult, CapUsage usage) {
        long points = 0;
        BigDecimal cash = BigDecimal.ZERO;
        boolean cashRule = r.awardsCash(programType);
        boolean pointsRule = r.awardsPoints(programType);
        if (pointsRule && r.pointsPerUnit() != null) {
            points = t.amount().multiply(r.pointsPerUnit()).multiply(r.multiplier()).multiply(tierMult)
                    .setScale(0, RoundingMode.FLOOR).longValueExact();
        }
        if (cashRule && r.cashbackRate() != null) {
            cash = t.amount().multiply(r.cashbackRate()).multiply(r.multiplier()).setScale(2, RoundingMode.HALF_EVEN);
        }
        if (r.fixedAward() != null && r.fixedAward().signum() > 0) {
            if (cashRule && ("CASHBACK".equals(programType) || r.cashbackRate() != null)) {
                cash = cash.add(r.fixedAward().setScale(2, RoundingMode.HALF_EVEN));
            } else if (pointsRule) {
                points += r.fixedAward().setScale(0, RoundingMode.FLOOR).longValueExact();
            }
        }
        boolean capped = false;
        if (r.capAmount() != null && r.capPeriod() != null) {
            Used used = Used.NONE;
            if (r.periodCap()) {
                Instant[] w = capWindow(r.capPeriod(), r.zone(), t.at());
                used = usage == null ? Used.NONE : usage.used(r, w[0], w[1]);
            }
            if (cashRule && r.cashbackRate() != null || "CASHBACK".equals(programType)) {
                BigDecimal remaining = r.capAmount().subtract(used.cash()).max(BigDecimal.ZERO);
                if (cash.compareTo(remaining) > 0) {
                    cash = remaining.setScale(2, RoundingMode.FLOOR);
                    capped = true;
                }
            } else {
                long remaining = Math.max(0, r.capAmount().setScale(0, RoundingMode.FLOOR).longValue() - used.points());
                if (points > remaining) {
                    points = remaining;
                    capped = true;
                }
            }
        }
        return new Award(r.id(), r.name(), points, cash, capped);
    }

    /** [start, end) of the cap period containing {@code at} in the zone. */
    public static Instant[] capWindow(String period, ZoneId zone, OffsetDateTime at) {
        ZonedDateTime z = at.atZoneSameInstant(zone);
        ZonedDateTime day = z.toLocalDate().atStartOfDay(zone);
        ZonedDateTime start;
        ZonedDateTime end;
        switch (period.toUpperCase(Locale.ROOT)) {
            case "DAY" -> {
                start = day;
                end = day.plusDays(1);
            }
            case "WEEK" -> {
                start = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                end = start.plusWeeks(1);
            }
            case "MONTH" -> {
                start = day.withDayOfMonth(1);
                end = start.plusMonths(1);
            }
            case "QUARTER" -> {
                int q = z.get(IsoFields.QUARTER_OF_YEAR);
                start = day.withDayOfYear(1).withMonth((q - 1) * 3 + 1);
                end = start.plusMonths(3);
            }
            case "YEAR" -> {
                start = day.withDayOfYear(1);
                end = start.plusYears(1);
            }
            default -> {
                return new Instant[] {Instant.EPOCH, Instant.parse("9999-12-31T00:00:00Z")};
            }
        }
        return new Instant[] {start.toInstant(), end.toInstant()};
    }

    private static <T> List<T> nz(List<T> l) {
        return l == null ? List.of() : l;
    }
}
