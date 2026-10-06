package io.meridian.collections.domain;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Deterministic distribution of queue cases across collectors (assignment of new cases and full rebalance). */
public final class QueueDistribution {
    private QueueDistribution() {}

    public record QueuedCase(UUID id, String assignedTo) {}

    public record Result(Map<UUID, String> assignment, int moved, int unassigned, Map<String, Integer> perAgent) {}

    /**
     * The collector with the fewest open cases (ties: first in the queue's agent list) that is below capacity;
     * {@code null} when every collector is full or the queue has none (round-robin by load).
     */
    public static String leastLoaded(List<String> agents, Map<String, Integer> load, Integer capacity) {
        String best = null;
        int bestLoad = Integer.MAX_VALUE;
        for (String a : agents) {
            int l = load.getOrDefault(a, 0);
            if ((capacity == null || l < capacity) && l < bestLoad) {
                best = a;
                bestLoad = l;
            }
        }
        return best;
    }

    /**
     * Even redistribution: each collector gets floor(T/n) or one more (T = cases that fit the capacity), cases
     * already with a collector stay while that collector is under target (minimal moves), the rest are given to
     * collectors with spare target in list order. Cases are expected in priority order (highest first) so the
     * cases left unassigned when capacity runs out are the least urgent.
     */
    public static Result rebalance(List<String> agents, Integer capacity, List<QueuedCase> cases) {
        Map<UUID, String> out = new LinkedHashMap<>();
        Map<String, Integer> kept = new LinkedHashMap<>();
        agents.forEach(a -> kept.put(a, 0));
        if (agents.isEmpty()) {
            cases.forEach(c -> out.put(c.id(), null));
            int moved = (int) cases.stream().filter(c -> c.assignedTo() != null).count();
            return new Result(out, moved, cases.size(), kept);
        }
        int n = agents.size();
        int total = capacity == null ? cases.size() : (int) Math.min(cases.size(), (long) capacity * n);
        Map<String, Integer> target = new HashMap<>();
        for (int i = 0; i < n; i++) {
            target.put(agents.get(i), total / n + (i < total % n ? 1 : 0));
        }
        for (QueuedCase c : cases) {
            String a = c.assignedTo();
            if (a != null && target.containsKey(a) && kept.get(a) < target.get(a)) {
                out.put(c.id(), a);
                kept.merge(a, 1, Integer::sum);
            }
        }
        int unassigned = 0;
        for (QueuedCase c : cases) {
            if (out.containsKey(c.id())) {
                continue;
            }
            String chosen = null;
            for (String a : agents) {
                if (kept.get(a) < target.get(a)) {
                    chosen = a;
                    break;
                }
            }
            out.put(c.id(), chosen);
            if (chosen == null) {
                unassigned++;
            } else {
                kept.merge(chosen, 1, Integer::sum);
            }
        }
        int moved = 0;
        for (QueuedCase c : cases) {
            if (!Objects.equals(c.assignedTo(), out.get(c.id()))) {
                moved++;
            }
        }
        return new Result(out, moved, unassigned, kept);
    }
}
