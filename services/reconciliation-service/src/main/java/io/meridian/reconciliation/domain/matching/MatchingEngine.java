package io.meridian.reconciliation.domain.matching;

import io.meridian.reconciliation.domain.StatementLine.Direction;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Deterministic, explainable matching of external lines against internal items with an ordered {@link RuleSet}.
 *
 * <p>Each rule proposes candidates for the lines and items still open, each with a confidence in [0, 1] built from
 * score components (key / reference, amount fit, date distance, counterparty name). A candidate is auto-confirmed
 * only when it reaches the auto-match threshold and is unambiguous: it leads the runner-up for each of its lines
 * and for each of its items by the rule's margin. Other candidates at or above the suggestion threshold are kept as
 * suggestions. Later rules only see what earlier rules left open. Exact duplicates (same strong reference, amount,
 * direction and date) are never matched. What is left becomes breaks: lines with the best suggestion (or none), and
 * items in the break window not suggested anywhere.
 *
 * <p>Every proposal carries an explanation: rule, matched keys, amount fit (exact, tolerance, charges, original
 * amount, fee, FX), date distance, name similarity, the runner-up's score and the number of candidates.
 */
public final class MatchingEngine {

    public enum Reason { MISSING_INTERNAL, MISSING_EXTERNAL, AMOUNT_MISMATCH, DATE_MISMATCH, DUPLICATE,
        CURRENCY_MISMATCH, AMBIGUOUS, OTHER }

    /** A proposed link between lines and items, with its confidence and explanation. */
    public record Proposal(String ruleCode, MatchRule.Type ruleType, String matchType, List<UUID> externalIds,
                           List<UUID> internalIds, BigDecimal difference, double confidence, boolean ambiguous,
                           Map<String, Object> explanation) {}

    public record Break(Reason reason, UUID externalId, UUID internalId, BigDecimal amount, String currency,
                        Proposal suggestion) {}

    /** Matches (auto-confirmed), breaks with suggestions, and per-rule statistics. */
    public record Result(List<Proposal> matches, List<Break> breaks, Map<String, Map<String, Object>> ruleStats,
                         int externalCount, int internalCount, int duplicates) {

        public long autoMatchedExternal() {
            return matches.stream().mapToLong(p -> p.externalIds().size()).sum();
        }

        public long suggested() {
            return breaks.stream().filter(b -> b.externalId() != null && b.suggestion() != null).count();
        }
    }

    record Fit(String kind, double score, BigDecimal diff) {}

    /** A candidate before resolution. */
    private record Cand(MatchRule rule, List<ExternalLine> exts, List<InternalItem> ints, double score,
                        BigDecimal diff, Map<String, Object> explanation) {
        UUID firstExt() {
            return exts.isEmpty() ? null : exts.getFirst().id();
        }
    }

    private static final double MIN_COMPONENT = 0.0;
    private static final Comparator<ExternalLine> EXT_ORDER =
            Comparator.comparing(ExternalLine::valueDate).thenComparing(ExternalLine::id);
    private static final Comparator<InternalItem> INT_ORDER =
            Comparator.comparing(InternalItem::valueDate).thenComparing(InternalItem::id);

    private final RuleSet set;
    private final List<ExternalLine> exts;
    private final List<InternalItem> ints;
    private final Set<UUID> usedExt = new HashSet<>();
    private final Set<UUID> usedInt = new HashSet<>();
    private final Map<UUID, Proposal> suggestions = new HashMap<>();
    private final List<Proposal> matches = new ArrayList<>();
    private final Map<String, Map<String, Object>> stats = new LinkedHashMap<>();
    private final Map<String, Map<String, List<InternalItem>>> keyIndex = new HashMap<>();
    private final Map<String, TreeMap<Long, List<InternalItem>>> amountIndex = new HashMap<>();
    private final Map<String, List<InternalItem>> groupIndex = new HashMap<>();
    private final Map<String, List<InternalItem>> nameTokenIndex = new HashMap<>();
    private final Map<List<String>, Map<String, List<InternalItem>>> refIndexes = new HashMap<>();
    private final Map<List<String>, Map<UUID, Set<String>>> extTokens = new HashMap<>();

    private MatchingEngine(RuleSet set, List<ExternalLine> exts, List<InternalItem> ints) {
        this.set = set;
        this.exts = exts.stream().sorted(EXT_ORDER).toList();
        this.ints = ints.stream().sorted(INT_ORDER).toList();
    }

    /**
     * @param breakFrom only items with a value date in [breakFrom, breakTo] raise MISSING_EXTERNAL (items outside the
     *                  window were loaded for the date tolerance only)
     */
    public static Result run(RuleSet set, List<ExternalLine> externals, List<InternalItem> internals,
                             LocalDate breakFrom, LocalDate breakTo) {
        MatchingEngine e = new MatchingEngine(set, externals, internals);
        return e.execute(breakFrom, breakTo);
    }

    private Result execute(LocalDate breakFrom, LocalDate breakTo) {
        buildIndexes();
        List<Break> breaks = new ArrayList<>();
        int duplicates = duplicates(breaks);
        for (MatchRule rule : set.rules()) {
            if (!rule.enabled()) {
                continue;
            }
            Map<String, Object> s = stats.computeIfAbsent(rule.code(), k -> newStats(rule));
            List<Cand> cands = switch (rule.type()) {
                case EXACT_KEY -> perExternal(rule, this::exactKey);
                case REFERENCE_EXTRACT -> perExternal(rule, this::reference);
                case FUZZY -> perExternal(rule, this::fuzzy);
                case ONE_TO_MANY -> rule.keys().isEmpty() ? perExternal(rule, this::subsetWithoutKey)
                        : perExternal(rule, this::batchKeyed);
                case MANY_TO_ONE -> manyToOne(rule);
                case REVERSAL_PAIR -> reversals(rule);
            };
            s.put("candidates", ((Number) s.get("candidates")).longValue() + cands.size());
            resolve(rule, cands, s);
        }
        sameKeySuggestions();
        breaks.addAll(leftovers(breakFrom, breakTo));
        for (Proposal p : suggestions.values()) {
            Map<String, Object> s = stats.get(p.ruleCode());
            if (s != null && p.externalIds().stream().noneMatch(usedExt::contains)) {
                s.put("suggested", ((Number) s.get("suggested")).longValue() + 1);
            }
        }
        for (Map<String, Object> s : stats.values()) {
            long n = ((Number) s.get("auto_matches")).longValue();
            double sum = (Double) s.remove("confidence_sum");
            s.put("avg_confidence", n == 0 ? null : round(sum / n));
            if (n == 0) {
                s.put("min_confidence", null);
            }
        }
        return new Result(List.copyOf(matches), breaks, stats, exts.size(), ints.size(), duplicates);
    }

    // ------------------------------------------------------------------ indexes

    private void buildIndexes() {
        Map<String, List<InternalItem>> byReference = new HashMap<>();
        for (InternalItem i : ints) {
            indexKey("uetr", i.uetr(), i);
            indexKey("end_to_end_id", i.endToEndId(), i);
            indexKey("end_to_end_id", i.reference(), i);
            indexKey("instruction_id", i.instructionId(), i);
            indexKey("instruction_id", i.reference(), i);
            indexKey("bank_ref", i.reference(), i);
            amountIndex.computeIfAbsent(i.currency() + "|" + i.direction(), k -> new TreeMap<>())
                    .computeIfAbsent(minor(i.amount()), k -> new ArrayList<>()).add(i);
            String batch = Keys.norm(i.batchRef());
            if (batch != null) {
                groupIndex.computeIfAbsent(batch, k -> new ArrayList<>()).add(i);
            }
            String ref = Keys.norm(i.reference());
            if (ref != null) {
                byReference.computeIfAbsent(ref, k -> new ArrayList<>()).add(i);
            }
            if (i.counterpartyName() != null) {
                for (String t : NameSimilarity.tokens(i.counterpartyName())) {
                    if (t.length() >= 3) {
                        nameTokenIndex.computeIfAbsent(t, k -> new ArrayList<>()).add(i);
                    }
                }
            }
        }
        byReference.forEach((ref, items) -> {
            if (items.size() >= 2 && !groupIndex.containsKey(ref)) {
                groupIndex.put(ref, items);
            }
        });
    }

    private void indexKey(String key, String value, InternalItem i) {
        String v = Keys.norm(value);
        if (v != null && v.length() >= 4) {
            List<InternalItem> l = keyIndex.computeIfAbsent(key, k -> new HashMap<>()).computeIfAbsent(v, k -> new ArrayList<>());
            if (l.isEmpty() || l.getLast() != i) {
                l.add(i);
            }
        }
    }

    /** Reference tokens of the internal items for a pattern list: references, ids and pattern extractions. */
    private Map<String, List<InternalItem>> refIndex(List<String> patterns) {
        return refIndexes.computeIfAbsent(patterns, p -> {
            Map<String, List<InternalItem>> idx = new HashMap<>();
            for (InternalItem i : ints) {
                Set<String> toks = new LinkedHashSet<>();
                for (String v : new String[] {i.reference(), i.endToEndId(), i.instructionId()}) {
                    String n = Keys.norm(v);
                    if (Keys.usable(n)) {
                        toks.add(n);
                    }
                }
                for (String text : new String[] {i.reference(), i.narrative()}) {
                    if (text != null) {
                        for (String pat : p) {
                            for (String x : io.meridian.common.text.SafeRegex.extract(pat, 0, text, 5)) {
                                String n = Keys.norm(x);
                                if (Keys.usable(n)) {
                                    toks.add(n);
                                }
                            }
                        }
                    }
                }
                for (String t : toks) {
                    idx.computeIfAbsent(t, k -> new ArrayList<>()).add(i);
                }
            }
            return idx;
        });
    }

    private Set<String> tokensOf(ExternalLine e, List<String> patterns) {
        return extTokens.computeIfAbsent(patterns, k -> new HashMap<>())
                .computeIfAbsent(e.id(), k -> Keys.tokens(e.remittance(), patterns));
    }

    // ------------------------------------------------------------------ duplicates

    private int duplicates(List<Break> breaks) {
        Map<String, UUID> seen = new HashMap<>();
        int n = 0;
        for (ExternalLine e : exts) {
            String ref = Keys.norm(e.uetr() != null ? e.uetr() : e.bankRef() != null ? e.bankRef() : e.endToEndId());
            if (ref == null) {
                continue;
            }
            String key = ref + "|" + e.amount().stripTrailingZeros().toPlainString() + "|" + e.direction() + "|"
                    + e.valueDate() + "|" + e.currency() + "|" + e.reversal();
            if (seen.putIfAbsent(key, e.id()) != null) {
                usedExt.add(e.id());
                breaks.add(new Break(Reason.DUPLICATE, e.id(), null, e.amount(), e.currency(), null));
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ candidate generation

    private interface PerExternal {
        List<Cand> candidates(MatchRule rule, ExternalLine e);
    }

    private List<Cand> perExternal(MatchRule rule, PerExternal f) {
        List<Cand> out = new ArrayList<>();
        for (ExternalLine e : exts) {
            if (!usedExt.contains(e.id())) {
                out.addAll(f.candidates(rule, e));
            }
        }
        return out;
    }

    private List<Cand> exactKey(MatchRule rule, ExternalLine e) {
        Map<UUID, Cand> best = new LinkedHashMap<>();
        for (String k : rule.keys()) {
            String v = Keys.norm(e.key(k));
            if (v == null) {
                continue;
            }
            for (InternalItem i : keyIndex.getOrDefault(k, Map.of()).getOrDefault(v, List.of())) {
                if (usedInt.contains(i.id()) || i.direction() != e.direction()) {
                    continue;
                }
                Fit fit = fit(rule, e, e.amount(), i.amount(), i.currency());
                long days = days(e.valueDate(), i.valueDate());
                if (fit == null || days > rule.dateWindowDays()) {
                    continue;
                }
                Double name = NameSimilarity.score(e.counterpartyName(), i.counterpartyName());
                double nameFactor = name == null || name >= 0.6 ? 1.0 : 0.97;
                double date = 1 - 0.01 * days;
                double score = fit.score() * date * nameFactor;
                Map<String, Object> comp = components(1.0, fit, date, name, null);
                Map<String, Object> keys = new LinkedHashMap<>();
                keys.put(k, e.key(k));
                Cand c = cand(rule, List.of(e), List.of(i), score, fit.diff(), comp, keys, days);
                Cand prev = best.get(i.id());
                if (prev == null || prev.score() < c.score()) {
                    best.put(i.id(), c);
                }
            }
        }
        return new ArrayList<>(best.values());
    }

    private List<Cand> reference(MatchRule rule, ExternalLine e) {
        Set<String> tokens = tokensOf(e, rule.patterns());
        if (tokens.isEmpty()) {
            return List.of();
        }
        Map<String, List<InternalItem>> idx = refIndex(rule.patterns());
        Map<UUID, Cand> best = new LinkedHashMap<>();
        for (String t : tokens) {
            for (InternalItem i : idx.getOrDefault(t, List.of())) {
                if (usedInt.contains(i.id()) || i.direction() != e.direction()) {
                    continue;
                }
                Fit fit = fit(rule, e, e.amount(), i.amount(), i.currency());
                long days = days(e.valueDate(), i.valueDate());
                if (fit == null || days > rule.dateWindowDays()) {
                    continue;
                }
                Double name = NameSimilarity.score(e.counterpartyName(), i.counterpartyName());
                double nameFactor = name == null ? 1.0 : name >= 0.85 ? 1.03 : name < 0.5 ? 0.9 : 1.0;
                double date = 1 - 0.01 * days;
                double score = Math.min(1.0, 0.97 * fit.score() * date * nameFactor);
                Map<String, Object> keys = new LinkedHashMap<>();
                keys.put("remittance_reference", t);
                Cand c = cand(rule, List.of(e), List.of(i), score, fit.diff(), components(0.97, fit, date, name, null),
                        keys, days);
                Cand prev = best.get(i.id());
                if (prev == null || prev.score() < c.score()) {
                    best.put(i.id(), c);
                }
            }
        }
        return new ArrayList<>(best.values());
    }

    private List<Cand> fuzzy(MatchRule rule, ExternalLine e) {
        if (e.counterpartyName() == null) {
            return List.of();
        }
        List<Cand> out = new ArrayList<>();
        BigDecimal tol = maxTolerance(rule, e.amount());
        List<InternalItem> pool = new ArrayList<>(range(e.currency(), e.direction(), e.amount().subtract(tol),
                e.amount().add(tol)));
        if (e.originalAmount() != null && e.originalCurrency() != null && !e.originalCurrency().equals(e.currency())) {
            pool.addAll(range(e.originalCurrency(), e.direction(), e.originalAmount(), e.originalAmount()));
        }
        Set<String> extToks = null;
        for (InternalItem i : pool) {
            if (usedInt.contains(i.id()) || i.counterpartyName() == null) {
                continue;
            }
            long days = days(e.valueDate(), i.valueDate());
            if (days > rule.dateWindowDays()) {
                continue;
            }
            Double name = NameSimilarity.score(e.counterpartyName(), i.counterpartyName());
            if (name == null || name < rule.nameThreshold()) {
                continue;
            }
            Fit fit = fit(rule, e, e.amount(), i.amount(), i.currency());
            if (fit == null) {
                continue;
            }
            double date = rule.dateWindowDays() == 0 ? 1.0 : 1 - 0.5 * days / rule.dateWindowDays();
            double refBonus = 0;
            if (i.reference() != null || i.narrative() != null) {
                if (extToks == null) {
                    extToks = tokensOf(e, List.of());
                }
                Set<String> intToks = Keys.tokens((i.reference() == null ? "" : i.reference()) + " "
                        + (i.narrative() == null ? "" : i.narrative()), List.of());
                for (String t : extToks) {
                    if (intToks.contains(t)) {
                        refBonus = 0.03;
                        break;
                    }
                }
            }
            double score = Math.min(1.0, 0.5 * name + 0.3 * fit.score() + 0.2 * date + refBonus);
            Map<String, Object> comp = components(null, fit, date, name, refBonus == 0 ? null : refBonus);
            comp.put("weights", "name 0.5, amount 0.3, date 0.2");
            out.add(cand(rule, List.of(e), List.of(i), score, fit.diff(), comp, Map.of(), days));
        }
        return out;
    }

    /** One line against a group of items sharing a key (batch / bulk payment, card settlement net of fees). */
    private List<Cand> batchKeyed(MatchRule rule, ExternalLine e) {
        Map<String, String> keys = new LinkedHashMap<>();
        for (String k : rule.keys()) {
            if ("remittance".equals(k)) {
                for (String t : tokensOf(e, RuleSet.STANDARD_PATTERNS)) {
                    keys.putIfAbsent(t, "remittance");
                }
            } else {
                String v = Keys.norm(e.key(k));
                if (v != null) {
                    keys.putIfAbsent(v, k);
                }
            }
        }
        List<Cand> out = new ArrayList<>();
        Set<Set<UUID>> seenGroups = new HashSet<>();
        for (Map.Entry<String, String> key : keys.entrySet()) {
            List<InternalItem> group = groupIndex.get(key.getKey());
            if (group == null) {
                continue;
            }
            List<InternalItem> members = group.stream()
                    .filter(i -> !usedInt.contains(i.id()) && i.direction() == e.direction()
                            && i.currency().equals(e.currency()) && days(e.valueDate(), i.valueDate()) <= rule.dateWindowDays())
                    .toList();
            if (members.size() < 2 || members.size() > rule.maxGroupSize()
                    || !seenGroups.add(new HashSet<>(members.stream().map(InternalItem::id).toList()))) {
                continue;
            }
            Map<String, Object> keyInfo = Map.of(key.getValue(), key.getKey());
            BigDecimal sum = sum(members.stream().map(InternalItem::amount).toList());
            Fit fit = groupFit(rule, e, sum);
            long days = maxDays(e.valueDate(), members.stream().map(InternalItem::valueDate).toList());
            if (fit != null) {
                double score = 0.98 * fit.score();
                Map<String, Object> comp = components(1.0, fit, null, null, null);
                comp.put("group_size", members.size());
                out.add(cand(rule, List.of(e), members, score, fit.diff(), comp, keyInfo, days));
                continue;
            }
            Cand partial = subset(rule, e, members, true, keyInfo);
            if (partial != null) {
                out.add(partial);
            }
        }
        return out;
    }

    /** Keyless one-to-many: items of a similar counterparty in the window whose amounts add up to the line. */
    private List<Cand> subsetWithoutKey(MatchRule rule, ExternalLine e) {
        if (e.counterpartyName() == null) {
            return List.of();
        }
        Set<InternalItem> pool = new LinkedHashSet<>();
        for (String t : NameSimilarity.tokens(e.counterpartyName())) {
            for (InternalItem i : nameTokenIndex.getOrDefault(t, List.of())) {
                if (!usedInt.contains(i.id()) && i.direction() == e.direction() && i.currency().equals(e.currency())
                        && i.amount().compareTo(e.amount()) < 0 && days(e.valueDate(), i.valueDate()) <= rule.dateWindowDays()) {
                    pool.add(i);
                }
            }
        }
        List<InternalItem> members = pool.stream().filter(i -> {
            Double s = NameSimilarity.score(e.counterpartyName(), i.counterpartyName());
            return s != null && s >= rule.nameThreshold();
        }).toList();
        if (members.size() < 2) {
            return List.of();
        }
        Cand c = subset(rule, e, members, rule.autoWithoutKey(), Map.of("counterparty", e.counterpartyName()));
        return c == null ? List.of() : List.of(c);
    }

    /** Bounded subset-sum of the members to the line amount (or amount + stated charges). */
    private Cand subset(MatchRule rule, ExternalLine e, List<InternalItem> members, boolean autoAllowed,
                        Map<String, Object> keyInfo) {
        List<InternalItem> sorted = members.stream()
                .sorted(Comparator.comparingLong((InternalItem i) -> days(e.valueDate(), i.valueDate()))
                        .thenComparing(InternalItem::id))
                .limit(rule.maxCandidates()).toList();
        long[] values = sorted.stream().mapToLong(i -> minor(i.amount())).toArray();
        List<BigDecimal> targets = new ArrayList<>();
        targets.add(e.amount());
        if (e.charges() != null) {
            targets.add(e.amount().add(e.charges()));
        }
        for (BigDecimal target : targets) {
            SubsetSum.Result r = SubsetSum.find(values, minor(target), 2, Math.min(rule.maxGroupSize(), sorted.size()),
                    rule.maxSearchNodes());
            if (r.indices().isEmpty()) {
                continue;
            }
            List<InternalItem> chosen = r.indices().stream().map(sorted::get).toList();
            boolean unique = r.status() == SubsetSum.Status.UNIQUE;
            double score = unique ? (autoAllowed ? 0.93 : 0.8) : 0.6;
            Map<String, Object> comp = new LinkedHashMap<>();
            comp.put("subset_sum", r.status().name());
            comp.put("search_nodes", r.nodes());
            comp.put("pool", sorted.size());
            comp.put("group_size", chosen.size());
            if (target != e.amount()) {
                comp.put("amount_fit", "CHARGES");
            }
            BigDecimal diff = e.amount().subtract(sum(chosen.stream().map(InternalItem::amount).toList()));
            Cand c = cand(rule, List.of(e), chosen, score, diff, comp, keyInfo,
                    maxDays(e.valueDate(), chosen.stream().map(InternalItem::valueDate).toList()));
            if (!unique) {
                c.explanation().put("ambiguous", true);
            }
            return c;
        }
        return null;
    }

    /** Several lines against one item that carries their shared key (bulk credit split by the bank, sweeps). */
    private List<Cand> manyToOne(MatchRule rule) {
        Map<String, List<ExternalLine>> byKey = new HashMap<>();
        for (ExternalLine e : exts) {
            if (usedExt.contains(e.id())) {
                continue;
            }
            Set<String> keys = new LinkedHashSet<>();
            for (String k : rule.keys()) {
                if ("remittance".equals(k)) {
                    keys.addAll(tokensOf(e, RuleSet.STANDARD_PATTERNS));
                } else {
                    String v = Keys.norm(e.key(k));
                    if (v != null) {
                        keys.add(v);
                    }
                }
            }
            for (String k : keys) {
                byKey.computeIfAbsent(k, x -> new ArrayList<>()).add(e);
            }
        }
        List<Cand> out = new ArrayList<>();
        for (InternalItem i : ints) {
            if (usedInt.contains(i.id())) {
                continue;
            }
            Set<String> keys = new LinkedHashSet<>();
            for (String v : new String[] {i.reference(), i.endToEndId(), i.instructionId(), i.batchRef()}) {
                String n = Keys.norm(v);
                if (n != null && n.length() >= 4) {
                    keys.add(n);
                }
            }
            Set<Set<UUID>> seenGroups = new HashSet<>();
            for (String k : keys) {
                List<ExternalLine> group = byKey.getOrDefault(k, List.of()).stream()
                        .filter(e -> !usedExt.contains(e.id()) && e.direction() == i.direction()
                                && e.currency().equals(i.currency()) && days(e.valueDate(), i.valueDate()) <= rule.dateWindowDays())
                        .distinct().toList();
                if (group.size() < 2 || group.size() > rule.maxGroupSize()
                        || !seenGroups.add(new HashSet<>(group.stream().map(ExternalLine::id).toList()))) {
                    continue;
                }
                BigDecimal sum = sum(group.stream().map(ExternalLine::amount).toList());
                long days = group.stream().mapToLong(e -> days(e.valueDate(), i.valueDate())).max().orElse(0);
                Map<String, Object> keyInfo = Map.of("shared_key", k);
                if (sum.compareTo(i.amount()) == 0) {
                    Map<String, Object> comp = components(1.0, new Fit("EXACT", 1.0, BigDecimal.ZERO), null, null, null);
                    comp.put("group_size", group.size());
                    out.add(cand(rule, group, List.of(i), 0.97, BigDecimal.ZERO, comp, keyInfo, days));
                    continue;
                }
                List<ExternalLine> sorted = group.stream().limit(rule.maxCandidates()).toList();
                SubsetSum.Result r = SubsetSum.find(sorted.stream().mapToLong(e -> minor(e.amount())).toArray(),
                        minor(i.amount()), 2, Math.min(rule.maxGroupSize(), sorted.size()), rule.maxSearchNodes());
                if (r.indices().isEmpty()) {
                    continue;
                }
                List<ExternalLine> chosen = r.indices().stream().map(sorted::get).toList();
                boolean unique = r.status() == SubsetSum.Status.UNIQUE;
                Map<String, Object> comp = new LinkedHashMap<>();
                comp.put("subset_sum", r.status().name());
                comp.put("search_nodes", r.nodes());
                comp.put("group_size", chosen.size());
                Cand c = cand(rule, chosen, List.of(i), unique ? 0.92 : 0.6, BigDecimal.ZERO, comp, keyInfo, days);
                if (!unique) {
                    c.explanation().put("ambiguous", true);
                }
                out.add(c);
            }
        }
        return out;
    }

    /** A reversal with its original: line pairs (statement) and item pairs (ledger). */
    private List<Cand> reversals(MatchRule rule) {
        List<Cand> out = new ArrayList<>();
        Map<String, List<ExternalLine>> byKey = new HashMap<>();
        for (ExternalLine e : exts) {
            if (usedExt.contains(e.id())) {
                continue;
            }
            for (String k : rule.keys()) {
                String v = Keys.norm(e.key(k));
                if (v != null && v.length() >= 4) {
                    byKey.computeIfAbsent(k + ":" + v, x -> new ArrayList<>()).add(e);
                }
            }
        }
        for (ExternalLine r : exts) {
            if (usedExt.contains(r.id()) || (rule.requireReversalFlag() && !r.reversal())) {
                continue;
            }
            Map<UUID, Cand> best = new LinkedHashMap<>();
            for (String k : rule.keys()) {
                String v = Keys.norm(r.key(k));
                if (v == null || v.length() < 4) {
                    continue;
                }
                for (ExternalLine o : byKey.getOrDefault(k + ":" + v, List.of())) {
                    if (o == r || o.reversal() || o.direction() == r.direction() || !o.currency().equals(r.currency())
                            || o.amount().compareTo(r.amount()) != 0 || o.valueDate().isAfter(r.valueDate())
                            || days(o.valueDate(), r.valueDate()) > rule.dateWindowDays()) {
                        continue;
                    }
                    long days = days(o.valueDate(), r.valueDate());
                    double score = (r.reversal() ? 0.99 : 0.85) - 0.002 * days;
                    Map<String, Object> comp = new LinkedHashMap<>();
                    comp.put("reversal_flag", r.reversal());
                    comp.put("days", days);
                    Map<String, Object> keyInfo = Map.of(k, r.key(k));
                    best.putIfAbsent(o.id(), cand(rule, List.of(o, r), List.of(), score, BigDecimal.ZERO, comp,
                            keyInfo, days));
                }
            }
            out.addAll(best.values());
        }
        // ledger side: a reversal item with its original (same reference and amount, opposite direction)
        Map<String, List<InternalItem>> intByRef = new HashMap<>();
        for (InternalItem i : ints) {
            String v = Keys.norm(i.reference() != null ? i.reference() : i.endToEndId());
            if (!usedInt.contains(i.id()) && v != null && v.length() >= 4) {
                intByRef.computeIfAbsent(v, x -> new ArrayList<>()).add(i);
            }
        }
        for (InternalItem r : ints) {
            if (usedInt.contains(r.id()) || !r.reversal()) {
                continue;
            }
            String v = Keys.norm(r.reference() != null ? r.reference() : r.endToEndId());
            for (InternalItem o : intByRef.getOrDefault(v, List.of())) {
                if (o == r || o.reversal() || o.direction() == r.direction() || !o.currency().equals(r.currency())
                        || o.amount().compareTo(r.amount()) != 0 || days(o.valueDate(), r.valueDate()) > rule.dateWindowDays()) {
                    continue;
                }
                Map<String, Object> comp = new LinkedHashMap<>();
                comp.put("reversal_flag", true);
                comp.put("side", "internal");
                out.add(cand(rule, List.of(), List.of(o, r), 0.99, BigDecimal.ZERO, comp, Map.of("reference", v),
                        days(o.valueDate(), r.valueDate())));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ resolution

    /**
     * Auto-confirms unambiguous candidates at or above the threshold: the best of each of its lines and items,
     * leading every competing candidate on those lines and items by the margin. The rest become suggestions.
     */
    private void resolve(MatchRule rule, List<Cand> cands, Map<String, Object> s) {
        if (cands.isEmpty()) {
            return;
        }
        double threshold = rule.autoThreshold() != null ? rule.autoThreshold() : set.autoThreshold();
        // best and runner-up score per line and per item, across all candidates of this rule
        Map<UUID, double[]> extTop = new HashMap<>();
        Map<UUID, double[]> intTop = new HashMap<>();
        Map<UUID, Cand> extBest = new HashMap<>();
        Map<UUID, Cand> intBest = new HashMap<>();
        for (Cand c : cands) {
            for (ExternalLine e : c.exts()) {
                top(extTop, extBest, e.id(), c);
            }
            for (InternalItem i : c.ints()) {
                top(intTop, intBest, i.id(), c);
            }
        }
        List<Cand> ordered = new ArrayList<>(cands);
        ordered.sort(Comparator.comparingDouble(Cand::score).reversed()
                .thenComparing(c -> c.exts().isEmpty() ? c.ints().getFirst().id() : c.exts().getFirst().id()));
        for (Cand c : ordered) {
            boolean free = c.exts().stream().noneMatch(e -> usedExt.contains(e.id()))
                    && c.ints().stream().noneMatch(i -> usedInt.contains(i.id()));
            if (!free) {
                continue;
            }
            double runnerUp = 0;
            boolean leads = true;
            for (ExternalLine e : c.exts()) {
                double[] t = extTop.get(e.id());
                double other = extBest.get(e.id()) == c ? t[1] : t[0];
                runnerUp = Math.max(runnerUp, other);
                leads &= c.score() - other >= rule.minMargin();
            }
            for (InternalItem i : c.ints()) {
                double[] t = intTop.get(i.id());
                double other = intBest.get(i.id()) == c ? t[1] : t[0];
                runnerUp = Math.max(runnerUp, other);
                leads &= c.score() - other >= rule.minMargin();
            }
            boolean ambiguous = Boolean.TRUE.equals(c.explanation().get("ambiguous")) || !leads;
            c.explanation().put("runner_up", round(runnerUp));
            if (c.score() >= threshold && !ambiguous) {
                Proposal p = proposal(c, false);
                matches.add(p);
                c.exts().forEach(e -> usedExt.add(e.id()));
                c.ints().forEach(i -> usedInt.add(i.id()));
                s.put("auto_matches", ((Number) s.get("auto_matches")).longValue() + 1);
                s.put("external_matched", ((Number) s.get("external_matched")).longValue() + c.exts().size());
                s.put("internal_matched", ((Number) s.get("internal_matched")).longValue() + c.ints().size());
                s.put("confidence_sum", (Double) s.get("confidence_sum") + c.score());
                s.put("min_confidence", Math.min((Double) s.get("min_confidence"), round(c.score())));
            } else if (c.score() >= set.suggestThreshold() && !c.exts().isEmpty()) {
                c.explanation().put("ambiguous", ambiguous && c.score() >= threshold
                        || Boolean.TRUE.equals(c.explanation().get("ambiguous")));
                offer(proposal(c, Boolean.TRUE.equals(c.explanation().get("ambiguous"))));
            }
        }
    }

    private static void top(Map<UUID, double[]> top, Map<UUID, Cand> best, UUID id, Cand c) {
        double[] t = top.computeIfAbsent(id, k -> new double[] {-1, 0});
        if (c.score() > t[0]) {
            t[1] = Math.max(t[0], 0);
            t[0] = c.score();
            best.put(id, c);
        } else if (c.score() > t[1]) {
            t[1] = c.score();
        }
    }

    private void offer(Proposal p) {
        for (UUID e : p.externalIds()) {
            Proposal cur = suggestions.get(e);
            if (cur == null || cur.confidence() < p.confidence()) {
                suggestions.put(e, p);
            }
        }
    }

    /** Lines left without any suggestion but sharing a key with an open item get it suggested (amount / date off). */
    private void sameKeySuggestions() {
        for (ExternalLine e : exts) {
            if (usedExt.contains(e.id()) || suggestions.containsKey(e.id())) {
                continue;
            }
            InternalItem best = null;
            String bestKey = null;
            for (String k : List.of("uetr", "end_to_end_id", "instruction_id", "bank_ref")) {
                String v = Keys.norm(e.key(k));
                if (v == null) {
                    continue;
                }
                for (InternalItem i : keyIndex.getOrDefault(k, Map.of()).getOrDefault(v, List.of())) {
                    if (!usedInt.contains(i.id()) && i.direction() == e.direction() && (best == null
                            || i.amount().subtract(e.amount()).abs().compareTo(best.amount().subtract(e.amount()).abs()) < 0)) {
                        best = i;
                        bestKey = k;
                    }
                }
            }
            if (best != null) {
                Map<String, Object> ex = new LinkedHashMap<>();
                ex.put("rule", "same_key");
                ex.put("keys", Map.of(bestKey, e.key(bestKey)));
                ex.put("currency_differs", !best.currency().equals(e.currency()));
                ex.put("days", days(e.valueDate(), best.valueDate()));
                ex.put("score", 0.6);
                suggestions.put(e.id(), new Proposal("same_key", null, "ONE_TO_ONE", List.of(e.id()), List.of(best.id()),
                        e.amount().subtract(best.amount()), 0.6, false, ex));
            }
        }
    }

    private List<Break> leftovers(LocalDate breakFrom, LocalDate breakTo) {
        List<Break> out = new ArrayList<>();
        Set<UUID> suggestedInt = new HashSet<>();
        Map<UUID, ExternalLine> extById = new HashMap<>();
        exts.forEach(e -> extById.put(e.id(), e));
        Map<UUID, InternalItem> intById = new HashMap<>();
        ints.forEach(i -> intById.put(i.id(), i));
        for (ExternalLine e : exts) {
            if (usedExt.contains(e.id())) {
                continue;
            }
            Proposal s = suggestions.get(e.id());
            if (s != null && (s.internalIds().stream().anyMatch(usedInt::contains)
                    || s.externalIds().stream().anyMatch(usedExt::contains))) {
                s = null;
            }
            Reason reason;
            if (s == null) {
                reason = Reason.MISSING_INTERNAL;
            } else {
                suggestedInt.addAll(s.internalIds());
                InternalItem first = s.internalIds().isEmpty() ? null : intById.get(s.internalIds().getFirst());
                if (s.ambiguous()) {
                    reason = Reason.AMBIGUOUS;
                } else if (first != null && !first.currency().equals(e.currency())
                        && !(e.originalCurrency() != null && e.originalCurrency().equals(first.currency()))) {
                    reason = Reason.CURRENCY_MISMATCH;
                } else if (s.difference().signum() != 0) {
                    reason = Reason.AMOUNT_MISMATCH;
                } else if (first != null && !first.valueDate().equals(e.valueDate())) {
                    reason = Reason.DATE_MISMATCH;
                } else {
                    reason = Reason.OTHER;
                }
            }
            out.add(new Break(reason, e.id(), null, e.amount(), e.currency(), s));
        }
        for (InternalItem i : ints) {
            if (usedInt.contains(i.id()) || suggestedInt.contains(i.id())
                    || i.valueDate().isBefore(breakFrom) || i.valueDate().isAfter(breakTo)) {
                continue;
            }
            out.add(new Break(Reason.MISSING_EXTERNAL, null, i.id(), i.amount(), i.currency(), null));
        }
        return out;
    }

    // ------------------------------------------------------------------ scoring helpers

    /**
     * How the line amount relates to the item amount: exact; within tolerance; explained by stated charges or the
     * stated original (instructed) amount, also across currencies; a deducted fee; an FX difference.
     */
    static Fit fit(MatchRule r, ExternalLine e, BigDecimal extAmount, BigDecimal intAmount, String intCcy) {
        if (!e.currency().equals(intCcy)) {
            if (e.originalAmount() != null && intCcy.equals(e.originalCurrency())
                    && e.originalAmount().compareTo(intAmount) == 0) {
                return new Fit("ORIGINAL_AMOUNT", 0.98, BigDecimal.ZERO);
            }
            return null;
        }
        BigDecimal diff = extAmount.subtract(intAmount);
        BigDecimal abs = diff.abs();
        if (abs.signum() == 0) {
            return new Fit("EXACT", 1.0, BigDecimal.ZERO);
        }
        BigDecimal tol = r.amountTolerance().max(pct(r.amountTolerancePct(), intAmount));
        if (tol.signum() > 0 && abs.compareTo(tol) <= 0) {
            return new Fit("TOLERANCE", 0.99 - 0.02 * ratio(abs, tol), diff);
        }
        if (e.charges() != null && abs.compareTo(e.charges()) == 0) {
            return new Fit("CHARGES", 0.98, diff);
        }
        if (e.originalAmount() != null && e.currency().equals(e.originalCurrency())
                && e.originalAmount().compareTo(intAmount) == 0) {
            return new Fit("ORIGINAL_AMOUNT", 0.98, diff);
        }
        BigDecimal fee = r.feeTolerance().max(pct(r.feeTolerancePct(), intAmount));
        if (fee.signum() > 0 && abs.compareTo(fee) <= 0) {
            return new Fit("FEE", 0.97 - 0.02 * ratio(abs, fee), diff);
        }
        BigDecimal fx = pct(r.fxTolerancePct(), intAmount);
        if (fx.signum() > 0 && abs.compareTo(fx) <= 0) {
            return new Fit("FX", 0.96 - 0.02 * ratio(abs, fx), diff);
        }
        return null;
    }

    /** Fit of a line against the sum of a group: exact, tolerance, stated charges, or a net of fees (sum - fee). */
    private static Fit groupFit(MatchRule r, ExternalLine e, BigDecimal sum) {
        BigDecimal diff = e.amount().subtract(sum);
        BigDecimal abs = diff.abs();
        if (abs.signum() == 0) {
            return new Fit("EXACT", 1.0, BigDecimal.ZERO);
        }
        BigDecimal tol = r.amountTolerance().max(pct(r.amountTolerancePct(), sum));
        if (tol.signum() > 0 && abs.compareTo(tol) <= 0) {
            return new Fit("TOLERANCE", 0.99 - 0.02 * ratio(abs, tol), diff);
        }
        if (e.charges() != null && abs.compareTo(e.charges()) == 0) {
            return new Fit("CHARGES", 0.98, diff);
        }
        if (e.originalAmount() != null && e.originalAmount().compareTo(sum) == 0) {
            return new Fit("ORIGINAL_AMOUNT", 0.98, diff);
        }
        BigDecimal fee = r.feeTolerance().max(pct(r.feeTolerancePct(), sum));
        if (fee.signum() > 0 && diff.signum() < 0 && abs.compareTo(fee) <= 0) {
            return new Fit("NET_OF_FEES", 0.97 - 0.02 * ratio(abs, fee), diff);
        }
        return null;
    }

    private static BigDecimal maxTolerance(MatchRule r, BigDecimal amount) {
        BigDecimal t = r.amountTolerance().max(pct(r.amountTolerancePct(), amount));
        t = t.max(r.feeTolerance()).max(pct(r.feeTolerancePct(), amount)).max(pct(r.fxTolerancePct(), amount));
        return t;
    }

    private Collection<InternalItem> range(String ccy, Direction dir, BigDecimal from, BigDecimal to) {
        TreeMap<Long, List<InternalItem>> idx = amountIndex.get(ccy + "|" + dir);
        if (idx == null) {
            return List.of();
        }
        NavigableMap<Long, List<InternalItem>> sub = idx.subMap(minor(from), true, minor(to), true);
        List<InternalItem> out = new ArrayList<>();
        sub.values().forEach(out::addAll);
        return out;
    }

    private Map<String, Object> components(Double key, Fit fit, Double date, Double name, Double reference) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (key != null) {
            m.put("key", round(key));
        }
        if (fit != null) {
            m.put("amount", round(fit.score()));
            m.put("amount_fit", fit.kind());
        }
        if (date != null) {
            m.put("date", round(date));
        }
        if (name != null) {
            m.put("name", round(name));
        }
        if (reference != null) {
            m.put("reference_bonus", round(reference));
        }
        return m;
    }

    private Cand cand(MatchRule rule, List<ExternalLine> e, List<InternalItem> i, double score, BigDecimal diff,
                      Map<String, Object> components, Map<String, Object> keys, long days) {
        Map<String, Object> ex = new LinkedHashMap<>();
        ex.put("rule", rule.code());
        ex.put("type", rule.type().name());
        if (!keys.isEmpty()) {
            ex.put("keys", keys);
        }
        ex.put("score", round(Math.max(MIN_COMPONENT, score)));
        ex.put("components", components);
        ex.put("difference", diff.stripTrailingZeros().toPlainString());
        ex.put("days", days);
        return new Cand(rule, e, i, Math.max(0, Math.min(1, score)), diff, ex);
    }

    private Proposal proposal(Cand c, boolean ambiguous) {
        String type = c.rule().type() == MatchRule.Type.REVERSAL_PAIR ? "MANY_TO_MANY"
                : c.exts().size() == 1 && c.ints().size() == 1 ? "ONE_TO_ONE"
                : c.exts().size() == 1 ? "ONE_TO_MANY" : c.ints().size() == 1 ? "MANY_TO_ONE" : "MANY_TO_MANY";
        if (c.rule().type() == MatchRule.Type.REVERSAL_PAIR) {
            c.explanation().put("reversal_pair", true);
        }
        return new Proposal(c.rule().code(), c.rule().type(), type, c.exts().stream().map(ExternalLine::id).toList(),
                c.ints().stream().map(InternalItem::id).toList(), c.diff(), round(c.score()), ambiguous,
                c.explanation());
    }

    private static Map<String, Object> newStats(MatchRule rule) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("rule", rule.code());
        s.put("type", rule.type().name());
        s.put("candidates", 0L);
        s.put("auto_matches", 0L);
        s.put("external_matched", 0L);
        s.put("internal_matched", 0L);
        s.put("suggested", 0L);
        s.put("min_confidence", 1.0);
        s.put("confidence_sum", 0.0);
        return s;
    }

    static long minor(BigDecimal a) {
        return a.setScale(4, RoundingMode.HALF_EVEN).unscaledValue().longValueExact();
    }

    private static BigDecimal pct(BigDecimal pct, BigDecimal amount) {
        return pct.signum() == 0 ? BigDecimal.ZERO : amount.abs().multiply(pct).divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_EVEN);
    }

    private static double ratio(BigDecimal a, BigDecimal b) {
        return a.divide(b, 8, RoundingMode.HALF_EVEN).doubleValue();
    }

    private static BigDecimal sum(List<BigDecimal> l) {
        return l.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static long days(LocalDate a, LocalDate b) {
        return Math.abs(ChronoUnit.DAYS.between(a, b));
    }

    private static long maxDays(LocalDate a, List<LocalDate> l) {
        return l.stream().mapToLong(d -> days(a, d)).max().orElse(0);
    }

    static double round(double v) {
        return Math.round(v * 10_000) / 10_000.0;
    }
}
