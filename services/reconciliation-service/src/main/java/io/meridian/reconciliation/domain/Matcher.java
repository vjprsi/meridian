package io.meridian.reconciliation.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic rule-based matching of external records against internal ledger entries.
 * Rules run in priority order. ONE_TO_ONE pairs each external record with the closest compatible internal entry;
 * ONE_TO_MANY / MANY_TO_ONE group the "many" side by reference and compare sums. A match is compatible when the
 * currency is equal, every reference field in {@code match_fields} is equal, and, when listed, the amount is within
 * the amount tolerance, the value date within the date tolerance and the direction corresponds. Matches of rules
 * without auto-confirm become suggestions. What is left becomes breaks with a reason and, where possible, a
 * suggested counterpart.
 */
public final class Matcher {

    public enum Reason { MISSING_INTERNAL, MISSING_EXTERNAL, AMOUNT_MISMATCH, DATE_MISMATCH, DUPLICATE,
        CURRENCY_MISMATCH, OTHER }

    public record External(UUID id, String externalRef, String endToEndId, LocalDate valueDate,
                           StatementLine.Direction direction, BigDecimal amount, String currency) {}

    public record Internal(UUID id, String reference, LocalDate valueDate, StatementLine.Direction direction,
                           BigDecimal amount, String currency) {}

    public record Rule(UUID id, int priority, String matchType, Set<String> fields, BigDecimal amountTolerance,
                       int dateToleranceDays, boolean autoConfirm) {}

    /** A proposed link; auto-confirmed proposals become matches, the others suggestions. */
    public record Proposal(Rule rule, String matchType, List<UUID> externalIds, List<UUID> internalIds,
                           BigDecimal difference, BigDecimal confidence) {}

    public record Break(Reason reason, UUID externalId, UUID internalId, BigDecimal amount, String currency,
                        Proposal suggestion) {}

    public record Outcome(List<Proposal> matches, List<Break> breaks) {}

    public static final Set<String> REFERENCE_FIELDS = Set.of("end_to_end_id", "external_ref", "reference");
    public static final Set<String> FIELDS = Set.of("amount", "value_date", "currency", "direction", "end_to_end_id",
            "external_ref", "reference");
    private static final int SUGGESTION_WINDOW_DAYS = 7;

    private Matcher() {}

    /**
     * @param invertDirection true when the source reports from the counterparty's view (a bank statement CREDIT is
     *                        a DEBIT of our nostro account)
     * @param breakWindow     only internal entries whose value date is inside it raise MISSING_EXTERNAL
     */
    public static Outcome run(List<Rule> rules, List<External> externals, List<Internal> internals,
                              boolean invertDirection, LocalDate breakFrom, LocalDate breakTo) {
        List<External> ext = externals.stream()
                .sorted(Comparator.comparing(External::valueDate).thenComparing(External::id)).toList();
        List<Internal> in = internals.stream()
                .sorted(Comparator.comparing(Internal::valueDate).thenComparing(Internal::id)).toList();
        Set<UUID> usedExt = new HashSet<>();
        Set<UUID> usedInt = new HashSet<>();
        List<Proposal> matches = new ArrayList<>();
        List<Break> breaks = new ArrayList<>();

        // Duplicates: same reference, amount, direction and value date as an earlier record of the batch.
        Map<String, UUID> seen = new HashMap<>();
        for (External e : ext) {
            String ref = e.endToEndId() != null ? e.endToEndId() : e.externalRef();
            if (ref == null) {
                continue;
            }
            String key = ref + "|" + e.amount().stripTrailingZeros().toPlainString() + "|" + e.direction() + "|"
                    + e.valueDate() + "|" + e.currency();
            if (seen.putIfAbsent(key, e.id()) != null) {
                usedExt.add(e.id());
                breaks.add(new Break(Reason.DUPLICATE, e.id(), null, e.amount(), e.currency(), null));
            }
        }

        List<Rule> ordered = rules.stream()
                .sorted(Comparator.comparingInt(Rule::priority).thenComparing(Rule::id)).toList();
        List<Proposal> suggestions = new ArrayList<>();
        for (Rule rule : ordered) {
            List<Proposal> found = switch (rule.matchType()) {
                case "ONE_TO_ONE" -> oneToOne(rule, ext, in, usedExt, usedInt, invertDirection);
                case "ONE_TO_MANY" -> grouped(rule, ext, in, usedExt, usedInt, invertDirection, true);
                case "MANY_TO_ONE" -> grouped(rule, ext, in, usedExt, usedInt, invertDirection, false);
                default -> List.of();
            };
            for (Proposal p : found) {
                (rule.autoConfirm() ? matches : suggestions).add(p);
            }
        }

        for (Proposal s : suggestions) {
            External first = extById(ext, s.externalIds().getFirst());
            Internal i = intById(in, s.internalIds().getFirst());
            Reason reason = s.difference().signum() != 0 ? Reason.AMOUNT_MISMATCH
                    : s.externalIds().size() == 1 && s.internalIds().size() == 1
                            && !first.valueDate().equals(i.valueDate()) ? Reason.DATE_MISMATCH : Reason.OTHER;
            for (UUID id : s.externalIds()) {
                External e = extById(ext, id);
                breaks.add(new Break(reason, e.id(), null, e.amount(), e.currency(), s));
            }
        }

        // Unmatched external records: explain the break and suggest the most plausible counterpart.
        for (External e : ext) {
            if (usedExt.contains(e.id())) {
                continue;
            }
            usedExt.add(e.id());
            Optional<Internal> sameRef = in.stream().filter(i -> !usedInt.contains(i.id()))
                    .filter(i -> i.reference() != null && (i.reference().equals(e.endToEndId())
                            || i.reference().equals(e.externalRef())))
                    .findFirst();
            if (sameRef.isPresent()) {
                Internal i = sameRef.get();
                usedInt.add(i.id());
                Reason reason = !i.currency().equals(e.currency()) ? Reason.CURRENCY_MISMATCH
                        : i.amount().compareTo(e.amount()) != 0 ? Reason.AMOUNT_MISMATCH
                        : !i.valueDate().equals(e.valueDate()) ? Reason.DATE_MISMATCH : Reason.OTHER;
                breaks.add(new Break(reason, e.id(), null, e.amount(), e.currency(),
                        suggestion(e, i, new BigDecimal("0.9"))));
                continue;
            }
            Optional<Internal> near = in.stream().filter(i -> !usedInt.contains(i.id()))
                    .filter(i -> i.currency().equals(e.currency()) && directionOk(e, i, invertDirection))
                    .filter(i -> Math.abs(ChronoUnit.DAYS.between(e.valueDate(), i.valueDate())) <= SUGGESTION_WINDOW_DAYS)
                    .min(Comparator.comparing((Internal i) -> i.amount().subtract(e.amount()).abs())
                            .thenComparing(i -> Math.abs(ChronoUnit.DAYS.between(e.valueDate(), i.valueDate())))
                            .thenComparing(Internal::id));
            breaks.add(new Break(Reason.MISSING_INTERNAL, e.id(), null, e.amount(), e.currency(),
                    near.map(i -> suggestion(e, i, new BigDecimal("0.5"))).orElse(null)));
        }

        Set<UUID> suggested = new HashSet<>();
        breaks.stream().filter(b -> b.suggestion() != null).forEach(b -> suggested.addAll(b.suggestion().internalIds()));
        for (Internal i : in) {
            if (usedInt.contains(i.id()) || suggested.contains(i.id())
                    || i.valueDate().isBefore(breakFrom) || i.valueDate().isAfter(breakTo)) {
                continue;
            }
            breaks.add(new Break(Reason.MISSING_EXTERNAL, null, i.id(), i.amount(), i.currency(), null));
        }
        return new Outcome(matches, breaks);
    }

    private static List<Proposal> oneToOne(Rule rule, List<External> ext, List<Internal> in, Set<UUID> usedExt,
                                           Set<UUID> usedInt, boolean invert) {
        List<Proposal> out = new ArrayList<>();
        for (External e : ext) {
            if (usedExt.contains(e.id())) {
                continue;
            }
            Optional<Internal> best = in.stream()
                    .filter(i -> !usedInt.contains(i.id()) && compatible(rule, e, i.reference(), i.valueDate(),
                            i.direction(), i.amount(), i.currency(), invert))
                    .min(Comparator.comparing((Internal i) -> i.amount().subtract(e.amount()).abs())
                            .thenComparing(i -> Math.abs(ChronoUnit.DAYS.between(e.valueDate(), i.valueDate())))
                            .thenComparing(Internal::id));
            best.ifPresent(i -> {
                usedExt.add(e.id());
                usedInt.add(i.id());
                BigDecimal diff = e.amount().subtract(i.amount());
                out.add(new Proposal(rule, "ONE_TO_ONE", List.of(e.id()), List.of(i.id()), diff,
                        confidence(rule, diff, ChronoUnit.DAYS.between(e.valueDate(), i.valueDate()))));
            });
        }
        return out;
    }

    /** oneExternal = true: one external record vs several internal entries sharing its reference (and vice versa). */
    private static List<Proposal> grouped(Rule rule, List<External> ext, List<Internal> in, Set<UUID> usedExt,
                                          Set<UUID> usedInt, boolean invert, boolean oneExternal) {
        List<Proposal> out = new ArrayList<>();
        if (rule.fields().stream().noneMatch(REFERENCE_FIELDS::contains)) {
            return out;
        }
        if (oneExternal) {
            for (External e : ext) {
                if (usedExt.contains(e.id())) {
                    continue;
                }
                List<Internal> group = in.stream().filter(i -> !usedInt.contains(i.id()))
                        .filter(i -> refsEqual(rule, e, i.reference()) && i.currency().equals(e.currency())
                                && (!rule.fields().contains("direction") || directionOk(e, i, invert))
                                && dateOk(rule, e.valueDate(), i.valueDate()))
                        .toList();
                if (group.size() < 2) {
                    continue;
                }
                BigDecimal sum = group.stream().map(Internal::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal diff = e.amount().subtract(sum);
                if (rule.fields().contains("amount") && diff.abs().compareTo(rule.amountTolerance()) > 0) {
                    continue;
                }
                usedExt.add(e.id());
                group.forEach(i -> usedInt.add(i.id()));
                out.add(new Proposal(rule, "ONE_TO_MANY", List.of(e.id()), group.stream().map(Internal::id).sorted().toList(),
                        diff, confidence(rule, diff, 0)));
            }
        } else {
            Map<String, List<External>> byRef = new LinkedHashMap<>();
            for (External e : ext) {
                String ref = e.endToEndId() != null ? e.endToEndId() : e.externalRef();
                if (!usedExt.contains(e.id()) && ref != null) {
                    byRef.computeIfAbsent(ref + "|" + e.currency(), k -> new ArrayList<>()).add(e);
                }
            }
            for (List<External> group : byRef.values()) {
                if (group.size() < 2) {
                    continue;
                }
                External head = group.getFirst();
                BigDecimal sum = group.stream().map(External::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
                Optional<Internal> target = in.stream().filter(i -> !usedInt.contains(i.id()))
                        .filter(i -> refsEqual(rule, head, i.reference()) && i.currency().equals(head.currency())
                                && dateOk(rule, head.valueDate(), i.valueDate())
                                && (!rule.fields().contains("amount")
                                    || sum.subtract(i.amount()).abs().compareTo(rule.amountTolerance()) <= 0))
                        .findFirst();
                target.ifPresent(i -> {
                    group.forEach(e -> usedExt.add(e.id()));
                    usedInt.add(i.id());
                    BigDecimal diff = sum.subtract(i.amount());
                    out.add(new Proposal(rule, "MANY_TO_ONE", group.stream().map(External::id).sorted().toList(),
                            List.of(i.id()), diff, confidence(rule, diff, 0)));
                });
            }
        }
        return out;
    }

    static boolean compatible(Rule rule, External e, String reference, LocalDate valueDate,
                              StatementLine.Direction direction, BigDecimal amount, String currency, boolean invert) {
        if (!e.currency().equals(currency)) {
            return false;
        }
        if (!refsEqual(rule, e, reference)) {
            return false;
        }
        if (rule.fields().contains("direction") && expected(e.direction(), invert) != direction) {
            return false;
        }
        if (rule.fields().contains("amount") && e.amount().subtract(amount).abs().compareTo(rule.amountTolerance()) > 0) {
            return false;
        }
        return dateOk(rule, e.valueDate(), valueDate);
    }

    private static boolean refsEqual(Rule rule, External e, String reference) {
        for (String f : rule.fields()) {
            String ext = switch (f) {
                case "end_to_end_id" -> e.endToEndId();
                case "external_ref" -> e.externalRef();
                case "reference" -> e.endToEndId() != null ? e.endToEndId() : e.externalRef();
                default -> null;
            };
            if (REFERENCE_FIELDS.contains(f) && (ext == null || !Objects.equals(ext, reference))) {
                return false;
            }
        }
        return true;
    }

    private static boolean dateOk(Rule rule, LocalDate a, LocalDate b) {
        return !rule.fields().contains("value_date")
                || Math.abs(ChronoUnit.DAYS.between(a, b)) <= rule.dateToleranceDays();
    }

    private static boolean directionOk(External e, Internal i, boolean invert) {
        return expected(e.direction(), invert) == i.direction();
    }

    private static StatementLine.Direction expected(StatementLine.Direction d, boolean invert) {
        if (!invert) {
            return d;
        }
        return d == StatementLine.Direction.CREDIT ? StatementLine.Direction.DEBIT : StatementLine.Direction.CREDIT;
    }

    /** 1.0 for an exact match, reduced linearly by the share of the tolerances used (amount 30%, date 20%). */
    static BigDecimal confidence(Rule rule, BigDecimal diff, long days) {
        double c = 1.0;
        if (diff.signum() != 0 && rule.amountTolerance().signum() > 0) {
            c -= 0.3 * diff.abs().divide(rule.amountTolerance(), 10, RoundingMode.HALF_EVEN).doubleValue();
        }
        if (days != 0 && rule.dateToleranceDays() > 0) {
            c -= 0.2 * Math.abs(days) / rule.dateToleranceDays();
        }
        return BigDecimal.valueOf(Math.max(0.0, c)).setScale(4, RoundingMode.HALF_EVEN);
    }

    private static Proposal suggestion(External e, Internal i, BigDecimal confidence) {
        return new Proposal(null, "ONE_TO_ONE", List.of(e.id()), List.of(i.id()), e.amount().subtract(i.amount()),
                confidence);
    }

    private static External extById(List<External> l, UUID id) {
        return l.stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
    }

    private static Internal intById(List<Internal> l, UUID id) {
        return l.stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
    }
}
