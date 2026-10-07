package io.meridian.reconciliation.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.reconciliation.domain.matching.MatchingEngine;
import io.meridian.reconciliation.domain.matching.RuleSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * S19 exit criterion "daily reconciliation auto-match rate above 98%": the standard rule set on a deterministic
 * synthetic day range (10k+ statement lines with duplicates, partial references, fee deductions, FX differences,
 * batch and card settlement nets, bulk splits, reversals, name typos, date shifts, decoys and genuine breaks)
 * auto-matches at least 98% of the lines that have a counterpart, with zero false auto-matches. Prints precision /
 * recall per rule and per scenario (see docs/PERFORMANCE.md, Reconciliation).
 */
class ReconBenchmarkTest {
    static final long SEED = 20261006L;
    static final int LINES = 10_000;

    @Test
    void autoMatchRateAbove98PercentWithFullPrecision() {
        ReconBenchmarkGenerator.Dataset ds = ReconBenchmarkGenerator.generate(SEED, LINES);
        long t0 = System.nanoTime();
        MatchingEngine.Result res = MatchingEngine.run(RuleSet.standard(), ds.externals(), ds.internals(), ds.from(), ds.to());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        Report r = evaluate(ds, res);
        System.out.println(r.text(ms, ds, res));

        assertThat(ds.externals().size()).isGreaterThanOrEqualTo(LINES);
        assertThat(r.falseMatches).as("false auto-matches: " + r.falseExamples).isZero();
        assertThat(r.recall()).as("auto-match rate over matchable lines").isGreaterThanOrEqualTo(0.98);
        assertThat(r.overall()).as("auto-match rate over all statement lines").isGreaterThanOrEqualTo(0.97);
    }

    @Test
    void benchmarkIsDeterministic() {
        ReconBenchmarkGenerator.Dataset a = ReconBenchmarkGenerator.generate(7L, 2_000);
        ReconBenchmarkGenerator.Dataset b = ReconBenchmarkGenerator.generate(7L, 2_000);
        assertThat(a.externals()).isEqualTo(b.externals());
        assertThat(a.internals()).isEqualTo(b.internals());
        MatchingEngine.Result ra = MatchingEngine.run(RuleSet.standard(), a.externals(), a.internals(), a.from(), a.to());
        MatchingEngine.Result rb = MatchingEngine.run(RuleSet.standard(), b.externals(), b.internals(), b.from(), b.to());
        assertThat(ra.matches()).isEqualTo(rb.matches());
        // other seeds hold the same bar
        for (long seed : new long[] {1L, 42L, 4711L}) {
            ReconBenchmarkGenerator.Dataset ds = ReconBenchmarkGenerator.generate(seed, 5_000);
            Report r = evaluate(ds, MatchingEngine.run(RuleSet.standard(), ds.externals(), ds.internals(), ds.from(), ds.to()));
            assertThat(r.falseMatches).as("seed " + seed + " false matches " + r.falseExamples).isZero();
            assertThat(r.recall()).as("seed " + seed).isGreaterThanOrEqualTo(0.98);
        }
    }

    static Report evaluate(ReconBenchmarkGenerator.Dataset ds, MatchingEngine.Result res) {
        Map<UUID, ReconBenchmarkGenerator.Truth> byExt = new HashMap<>();
        Map<UUID, ReconBenchmarkGenerator.Truth> byInt = new HashMap<>();
        for (ReconBenchmarkGenerator.Truth t : ds.truth()) {
            t.externalIds().forEach(e -> byExt.put(e, t));
            t.internalIds().forEach(i -> byInt.put(i, t));
        }
        Report r = new Report();
        for (ReconBenchmarkGenerator.Truth t : ds.truth()) {
            if (t.matchable()) {
                r.matchable += t.externalIds().size();
                r.scenario(t.scenario())[0] += t.externalIds().size();
            }
        }
        for (MatchingEngine.Proposal p : res.matches()) {
            ReconBenchmarkGenerator.Truth t = !p.externalIds().isEmpty() ? byExt.get(p.externalIds().getFirst())
                    : byInt.get(p.internalIds().getFirst());
            boolean correct = t != null && t.externalIds().equals(new HashSet<>(p.externalIds()))
                    && t.internalIds().equals(new HashSet<>(p.internalIds()));
            long[] rule = r.rule(p.ruleCode());
            rule[0]++;
            rule[1] += p.externalIds().size();
            if (correct) {
                rule[2]++;
                r.correctLines += p.externalIds().size();
                r.scenario(t.scenario())[1] += p.externalIds().size();
            } else {
                r.falseMatches++;
                if (r.falseExamples.size() < 5) {
                    r.falseExamples.add(p.ruleCode() + " " + p.explanation() + " truth=" + (t == null ? "none" : t.scenario()));
                }
            }
            r.autoLines += p.externalIds().size();
        }
        return r;
    }

    static final class Report {
        long matchable;
        long correctLines;
        long autoLines;
        long falseMatches;
        final List<String> falseExamples = new java.util.ArrayList<>();
        final Map<String, long[]> rules = new java.util.LinkedHashMap<>();
        final Map<String, long[]> scenarios = new TreeMap<>();
        int total;

        long[] rule(String code) {
            return rules.computeIfAbsent(code, k -> new long[3]);
        }

        long[] scenario(String s) {
            return scenarios.computeIfAbsent(s, k -> new long[2]);
        }

        double recall() {
            return matchable == 0 ? 0 : (double) correctLines / matchable;
        }

        double overall() {
            return total == 0 ? 0 : (double) autoLines / total;
        }

        String text(long ms, ReconBenchmarkGenerator.Dataset ds, MatchingEngine.Result res) {
            total = ds.externals().size();
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%nReconciliation benchmark (seed %d): %d statement lines, %d internal items, engine %d ms%n",
                    SEED, ds.externals().size(), ds.internals().size(), ms));
            sb.append(String.format("auto-matched lines %d (%.2f%% of all lines), matchable %d, correct %d -> auto-match rate %.2f%%, "
                    + "false auto-matches %d (precision %.4f)%n", autoLines, 100.0 * autoLines / total, matchable,
                    correctLines, 100 * recall(), falseMatches,
                    autoLines == 0 ? 0 : (double) correctLines / autoLines));
            sb.append(String.format("suggested lines %d, duplicates flagged %d%n", res.suggested(), res.duplicates()));
            sb.append("| rule | auto matches | lines | correct | precision |\n|---|---:|---:|---:|---:|\n");
            rules.forEach((k, v) -> sb.append(String.format("| %s | %d | %d | %d | %.4f |%n", k, v[0], v[1], v[2],
                    v[0] == 0 ? 0 : (double) v[2] / v[0])));
            sb.append("| scenario | matchable lines | auto-matched correctly | recall |\n|---|---:|---:|---:|\n");
            scenarios.forEach((k, v) -> sb.append(String.format("| %s | %d | %d | %.4f |%n", k, v[0], v[1],
                    v[0] == 0 ? 1.0 : (double) v[1] / v[0])));
            Set<String> seen = new HashSet<>();
            res.ruleStats().forEach((k, v) -> seen.add(k));
            sb.append("rule stats: ").append(res.ruleStats()).append('\n');
            sb.append("false examples: ").append(falseExamples).append('\n');
            return sb.toString();
        }
    }
}
