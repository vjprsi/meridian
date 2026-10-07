package io.meridian.fraud.domain.features;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

/**
 * In-memory reference of one entity's bucketed counters, with exactly the storage semantics of
 * {@code fraud_feature_counter} (an event adds to its 5-minute and its hourly bucket; windows sum whole buckets as
 * {@link Buckets.Window} defines). The database implementation must agree with it; tests check both against a
 * brute-force scan of the raw events.
 */
public final class WindowCounter {
    private final TreeMap<Instant, double[]> m5 = new TreeMap<>();
    private final TreeMap<Instant, double[]> h1 = new TreeMap<>();

    public void add(Instant t, double amount) {
        bump(m5, Buckets.floor(t, Buckets.M5), amount);
        bump(h1, Buckets.floor(t, Buckets.H1), amount);
    }

    /** Count and amount sum of the window at {@code now}. */
    public double[] window(Buckets.Window w, Instant now) {
        TreeMap<Instant, double[]> m = w.bucket.equals(Buckets.M5) ? m5 : h1;
        double count = 0;
        double amount = 0;
        for (Map.Entry<Instant, double[]> e : m.tailMap(w.start(now), true).entrySet()) {
            if (e.getKey().isAfter(now)) {
                break;
            }
            count += e.getValue()[0];
            amount += e.getValue()[1];
        }
        return new double[] {count, amount};
    }

    /** Drops buckets older than their retention at {@code now} (bounded memory). */
    public void prune(Instant now) {
        m5.headMap(now.minus(Buckets.M5_RETENTION)).clear();
        h1.headMap(now.minus(Buckets.H1_RETENTION)).clear();
    }

    public int buckets() {
        return m5.size() + h1.size();
    }

    private static void bump(TreeMap<Instant, double[]> m, Instant k, double amount) {
        double[] v = m.computeIfAbsent(k, x -> new double[2]);
        v[0] += 1;
        v[1] += amount;
    }
}
