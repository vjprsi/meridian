package io.meridian.reconciliation.domain.matching;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Bounded subset-sum over positive amounts in minor units: finds a subset of between {@code minSize} and
 * {@code maxSize} items summing exactly to the target, by depth-first search over the amounts sorted descending,
 * pruned when the running sum overshoots or the remaining items cannot reach the target. The search visits at most
 * {@code maxNodes} nodes and stops after a second solution, so a caller can tell a unique solution (safe to
 * auto-match) from an ambiguous one or an exhausted budget (suggest only).
 */
public final class SubsetSum {

    public enum Status { UNIQUE, AMBIGUOUS, NOT_FOUND, BUDGET_EXHAUSTED }

    /** @param indices indices into the input of the first solution found (empty when none) */
    public record Result(Status status, List<Integer> indices, long nodes) {}

    private SubsetSum() {}

    public static Result find(long[] values, long target, int minSize, int maxSize, long maxNodes) {
        int n = values.length;
        if (n == 0 || target <= 0) {
            return new Result(Status.NOT_FOUND, List.of(), 0);
        }
        Integer[] order = IntStream.range(0, n).boxed().toArray(Integer[]::new);
        Arrays.sort(order, Comparator.comparingLong((Integer i) -> values[i]).reversed().thenComparingInt(i -> i));
        long[] v = new long[n];
        for (int i = 0; i < n; i++) {
            v[i] = values[order[i]];
            if (v[i] <= 0) {
                throw new IllegalArgumentException("subset-sum values must be positive");
            }
        }
        long[] suffix = new long[n + 1];
        for (int i = n - 1; i >= 0; i--) {
            suffix[i] = suffix[i + 1] + v[i];
        }
        Search s = new Search(v, suffix, target, minSize, maxSize, maxNodes);
        s.dfs(0, 0, new int[Math.min(maxSize, n)], 0);
        Status status;
        if (s.solutions >= 2) {
            status = Status.AMBIGUOUS;
        } else if (s.exhausted) {
            status = Status.BUDGET_EXHAUSTED;
        } else if (s.solutions == 1) {
            status = Status.UNIQUE;
        } else {
            status = Status.NOT_FOUND;
        }
        List<Integer> idx = new ArrayList<>();
        if (s.first != null) {
            for (int i : s.first) {
                idx.add(order[i]);
            }
            idx.sort(Integer::compareTo);
        }
        return new Result(status, idx, s.nodes);
    }

    private static final class Search {
        final long[] v;
        final long[] suffix;
        final long target;
        final int minSize;
        final int maxSize;
        final long maxNodes;
        long nodes;
        int solutions;
        boolean exhausted;
        int[] first;

        Search(long[] v, long[] suffix, long target, int minSize, int maxSize, long maxNodes) {
            this.v = v;
            this.suffix = suffix;
            this.target = target;
            this.minSize = minSize;
            this.maxSize = maxSize;
            this.maxNodes = maxNodes;
        }

        /** @return true to stop the search */
        boolean dfs(int start, long sum, int[] chosen, int size) {
            if (sum == target) {
                if (size >= minSize) {
                    solutions++;
                    if (first == null) {
                        first = Arrays.copyOf(chosen, size);
                    }
                    return solutions >= 2;
                }
                return false; // positive values only: adding more can never return to the target
            }
            if (size == maxSize || start >= v.length || sum + suffix[start] < target) {
                return false;
            }
            for (int i = start; i < v.length; i++) {
                if (++nodes > maxNodes) {
                    exhausted = true;
                    return true;
                }
                if (sum + suffix[i] < target) {
                    return false; // the rest cannot reach the target
                }
                long next = sum + v[i];
                if (next > target) {
                    continue; // too large; smaller values follow
                }
                // equal amounts are distinct items: choosing either is a different solution (ambiguous identity)
                chosen[size] = i;
                if (dfs(i + 1, next, chosen, size + 1)) {
                    return true;
                }
            }
            return false;
        }
    }
}
