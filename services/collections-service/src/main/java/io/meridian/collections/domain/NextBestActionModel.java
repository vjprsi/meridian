package io.meridian.collections.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic, explainable next-best-action model. A logistic propensity-to-pay score built from additive,
 * named contributions (the reason codes), then a rule-scored choice among contact channels with conduct
 * constraints: disputed debts get written communication only, vulnerable customers never get legal notices or
 * field visits, the weekly contact cap defers the suggestion, and phone/SMS/push contact avoids quiet hours.
 */
public final class NextBestActionModel {
    private NextBestActionModel() {}

    public static final String MODEL_VERSION = "nba-rules-1.0";

    /** Candidate actions in tie-break order. */
    static final List<String> CANDIDATES = List.of("SMS", "PUSH", "EMAIL", "CALL", "LETTER", "FIELD_VISIT", "LEGAL_NOTICE");
    static final Set<String> WRITTEN = Set.of("LETTER", "EMAIL");

    public record Signals(int dpd, BigDecimal overdue, BigDecimal totalOutstanding, String currency, int brokenPromises,
                          Integer daysSinceLastPayment, int rightPartyContacts, int noAnswers, int refusals,
                          int contactsLast7Days, int maxContactsPerWeek, OffsetDateTime capFreesAt, boolean disputed,
                          boolean vulnerable, boolean promiseActive, boolean planActive, LocalTime quietStart,
                          LocalTime quietEnd, OffsetDateTime now) {}

    public record ReasonCode(String code, String description, BigDecimal contribution) {}

    public record Alternative(String action, BigDecimal score, String excludedBy) {}

    public record Suggestion(String action, OffsetDateTime at, BigDecimal propensity, List<ReasonCode> reasons,
                             List<Alternative> alternatives, Map<String, Object> offer, String explanation) {}

    public static Suggestion suggest(Signals s) {
        List<ReasonCode> contributions = new ArrayList<>();
        double z = 1.0;
        z += add(contributions, "DAYS_PAST_DUE", s.dpd() + " days past due", -0.02 * s.dpd());
        z += add(contributions, "BROKEN_PROMISES", s.brokenPromises() + " broken promises", -0.6 * s.brokenPromises());
        Integer since = s.daysSinceLastPayment();
        if (since == null) {
            z += add(contributions, "NO_PAYMENT_HISTORY", "No payment seen since the case opened", -0.5);
        } else if (since <= 30) {
            z += add(contributions, "RECENT_PAYMENT", "Paid " + since + " days ago", 0.8);
        } else if (since <= 90) {
            z += add(contributions, "PAYMENT_WITHIN_90_DAYS", "Paid " + since + " days ago", 0.2);
        } else {
            z += add(contributions, "NO_RECENT_PAYMENT", "Last payment " + since + " days ago", -0.4);
        }
        z += add(contributions, "RIGHT_PARTY_CONTACT", s.rightPartyContacts() + " right-party contacts",
                0.3 * Math.min(s.rightPartyContacts(), 3));
        z += add(contributions, "UNREACHABLE", s.noAnswers() + " unanswered contact attempts",
                -0.15 * Math.min(s.noAnswers(), 5));
        z += add(contributions, "REFUSED_TO_PAY", s.refusals() + " refusals to pay", -0.7 * Math.min(s.refusals(), 2));
        if (s.promiseActive()) {
            z += add(contributions, "ACTIVE_PROMISE", "A promise to pay is active", 0.3);
        }
        double p = 1.0 / (1.0 + Math.exp(-z));
        BigDecimal propensity = BigDecimal.valueOf(p).setScale(4, RoundingMode.HALF_EVEN);

        List<Alternative> alternatives = new ArrayList<>();
        for (String action : CANDIDATES) {
            String excluded = exclusion(action, s);
            double score = excluded == null ? score(action, s, p) : 0;
            alternatives.add(new Alternative(action, BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_EVEN), excluded));
        }
        Alternative best = alternatives.stream().filter(a -> a.excludedBy() == null)
                .max(Comparator.comparing(Alternative::score)
                        .thenComparing(a -> -CANDIDATES.indexOf(a.action())))
                .orElse(new Alternative("LETTER", BigDecimal.ZERO, null));
        List<Alternative> ranked = new ArrayList<>(alternatives);
        ranked.sort(Comparator.comparing(Alternative::score).reversed()
                .thenComparing(a -> CANDIDATES.indexOf(a.action())));

        List<ReasonCode> reasons = new ArrayList<>(contributions);
        reasons.sort(Comparator.comparing((ReasonCode r) -> r.contribution().abs()).reversed()
                .thenComparing(ReasonCode::code));
        boolean capReached = s.maxContactsPerWeek() > 0 && s.contactsLast7Days() >= s.maxContactsPerWeek();
        if (capReached) {
            reasons.add(0, new ReasonCode("CONTACT_CAP_REACHED", s.contactsLast7Days() + " contacts in 7 days (cap "
                    + s.maxContactsPerWeek() + "); contact deferred", BigDecimal.ZERO));
        }
        if (s.disputed()) {
            reasons.add(0, new ReasonCode("DEBT_DISPUTED", "Debt disputed: written communication only", BigDecimal.ZERO));
        }
        if (s.vulnerable()) {
            reasons.add(0, new ReasonCode("VULNERABLE_CUSTOMER", "Vulnerable customer: no legal notice or field visit",
                    BigDecimal.ZERO));
        }
        if (reasons.size() > 6) {
            reasons = new ArrayList<>(reasons.subList(0, 6));
        }

        OffsetDateTime at = s.now();
        if (capReached && s.capFreesAt() != null && s.capFreesAt().isAfter(at)) {
            at = s.capFreesAt();
        }
        if (!WRITTEN.contains(best.action())) {
            at = outsideQuietHours(at, s.quietStart(), s.quietEnd());
        }

        Map<String, Object> offer = offer(s, p);
        StringBuilder why = new StringBuilder("Suggest ").append(best.action()).append(" (score ")
                .append(best.score().toPlainString()).append("); propensity to pay ").append(propensity.toPlainString())
                .append(". Main factors: ");
        int n = 0;
        for (ReasonCode r : reasons) {
            if (n++ == 3) {
                break;
            }
            why.append(n > 1 ? "; " : "").append(r.code()).append(" ").append(r.contribution().toPlainString());
        }
        if (offer != null) {
            why.append(". Offer: ").append(offer.get("type"));
        }
        return new Suggestion(best.action(), at, propensity, reasons, ranked, offer, why.toString());
    }

    static String exclusion(String action, Signals s) {
        if (s.disputed() && !WRITTEN.contains(action)) {
            return "DEBT_DISPUTED";
        }
        if (s.vulnerable() && (action.equals("LEGAL_NOTICE") || action.equals("FIELD_VISIT"))) {
            return "VULNERABLE_CUSTOMER";
        }
        if (action.equals("FIELD_VISIT") && s.dpd() < 60) {
            return "DPD_BELOW_60";
        }
        if (action.equals("LEGAL_NOTICE") && s.dpd() < 90) {
            return "DPD_BELOW_90";
        }
        return null;
    }

    static double score(String action, Signals s, double p) {
        int dpd = s.dpd();
        double score = switch (action) {
            case "SMS" -> 40 + (dpd < 30 ? 25 : 0) + (p >= 0.5 ? 15 : 0) + (s.promiseActive() ? 30 : 0);
            case "PUSH" -> 35 + (dpd < 15 ? 25 : 0) + (p >= 0.5 ? 15 : 0);
            case "EMAIL" -> 35 + (dpd < 30 ? 15 : 0) + (s.disputed() ? 30 : 0);
            case "CALL" -> 40 + (dpd >= 15 ? 25 : 0) + (p >= 0.25 && p < 0.75 ? 15 : 0)
                    + 5 * Math.min(s.rightPartyContacts(), 3) - 5 * Math.min(s.noAnswers(), 5) + (s.vulnerable() ? 15 : 0);
            case "LETTER" -> 25 + (dpd >= 30 ? 20 : 0) + (s.noAnswers() >= 3 ? 15 : 0) + (s.disputed() ? 40 : 0);
            case "FIELD_VISIT" -> 30 + (s.noAnswers() >= 3 ? 25 : 0) + (p < 0.3 ? 10 : 0);
            case "LEGAL_NOTICE" -> 30 + 10 * Math.min(s.brokenPromises(), 3) + (p < 0.3 ? 20 : 0);
            default -> 0;
        };
        if (s.promiseActive() && !action.equals("SMS")) {
            score -= 20; // the customer committed to pay: a reminder beats pressure
        }
        return Math.max(0, score);
    }

    static Map<String, Object> offer(Signals s, double p) {
        if (s.planActive() || s.disputed() || !(s.brokenPromises() >= 2 || s.dpd() >= 60)) {
            return null;
        }
        Map<String, Object> o = new LinkedHashMap<>();
        if (s.dpd() >= 120 && p < 0.3 && s.totalOutstanding() != null) {
            BigDecimal discount = new BigDecimal("0.20");
            o.put("type", "SETTLEMENT");
            o.put("discount_pct", discount.toPlainString());
            o.put("settlement_amount", Currencies.round(s.totalOutstanding().multiply(BigDecimal.ONE.subtract(discount)),
                    s.currency()).toPlainString());
        } else {
            int count = s.overdue() != null && s.overdue().compareTo(new BigDecimal("1000")) > 0 ? 12 : 6;
            o.put("type", "PAYMENT_PLAN");
            o.put("installment_count", count);
            o.put("frequency", "MONTHLY");
            if (s.overdue() != null) {
                o.put("installment_amount", s.overdue().divide(BigDecimal.valueOf(count),
                        Currencies.digits(s.currency()), RoundingMode.UP).toPlainString());
            }
        }
        o.put("currency", s.currency());
        return o;
    }

    /** Moves a contact time out of the quiet window (which may wrap midnight) to the window's end. */
    public static OffsetDateTime outsideQuietHours(OffsetDateTime at, LocalTime start, LocalTime end) {
        if (start == null || end == null || start.equals(end)) {
            return at;
        }
        LocalTime t = at.toLocalTime();
        boolean wraps = start.isAfter(end);
        boolean quiet = wraps ? !t.isBefore(start) || t.isBefore(end) : !t.isBefore(start) && t.isBefore(end);
        if (!quiet) {
            return at;
        }
        OffsetDateTime sameDayEnd = at.with(end).withSecond(0).withNano(0);
        return wraps && !t.isBefore(start) ? sameDayEnd.plusDays(1) : sameDayEnd;
    }

    private static double add(List<ReasonCode> out, String code, String description, double contribution) {
        if (contribution != 0) {
            out.add(new ReasonCode(code, description, BigDecimal.valueOf(contribution).setScale(2, RoundingMode.HALF_EVEN)));
        }
        return contribution;
    }
}
