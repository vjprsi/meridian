package io.meridian.fraud.ml;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * SYNTHETIC labelled fraud data for the sample models. Nothing here comes from real customers or real fraud: the
 * generator draws legitimate and fraudulent events from hand-picked distributions (fraud: higher velocity, new
 * payees / devices / countries, amounts far above the customer's usual, odd hours, risky merchants, young
 * beneficiaries, remote-access tools). It exists so that tests can train, register and score a model end to end,
 * deterministically (fixed seed, {@link SplittableRandom}). Models trained on it must never be used for real
 * decisions.
 */
public final class SyntheticFraudData {
    /** Feature order of the sample models (all are catalogued feature-store / evaluation features). */
    public static final List<String> FEATURES = List.of(
            "customer_txn_count_1h", "customer_txn_count_24h", "customer_txn_amount_24h", "card_txn_count_1h",
            "device_txn_count_24h", "new_payee", "new_device_for_customer", "new_country_for_customer",
            "new_merchant_for_card", "amount_zscore", "hour_deviation", "merchant_risk", "beneficiary_age_days",
            "txn_amount", "customer_tenure_days", "remote_access", "device_emulator");

    public static final long SEED = 20261007L;
    public static final double FRAUD_RATE = 0.12;

    public record Row(double[] x, int label) {}

    private SyntheticFraudData() {}

    public static List<Row> generate(int n, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        List<Row> rows = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            boolean fraud = r.nextDouble() < FRAUD_RATE;
            // a quarter of the frauds look entirely legitimate (undetectable from these features)
            boolean camouflaged = fraud && r.nextDouble() < 0.25;
            rows.add(new Row(fraud && !camouflaged ? fraud(r) : legit(r), fraud ? 1 : 0));
        }
        return rows;
    }

    private static double[] legit(SplittableRandom r) {
        double c1h = poisson(r, 0.3);
        double c24 = c1h + poisson(r, 2.0);
        double amount = round2(StrictMath.exp(3.5 + 1.0 * gauss(r)));
        return new double[] {
            c1h, c24, round2(c24 * amount * (0.6 + 0.8 * r.nextDouble())), poisson(r, 0.2), poisson(r, 2.5),
            bit(r, 0.10), bit(r, 0.05), bit(r, 0.04), bit(r, 0.30), round4(clamp(gauss(r), -10, 10)),
            round4(0.15 * r.nextDouble()), round6(0.0005 + 0.002 * r.nextDouble()),
            r.nextDouble() < 0.4 ? Double.NaN : round2(30 + 400 * r.nextDouble()), amount,
            round2(200 + 2000 * r.nextDouble()), bit(r, 0.005), bit(r, 0.003)
        };
    }

    private static double[] fraud(SplittableRandom r) {
        double c1h = poisson(r, 0.9);
        double c24 = c1h + poisson(r, 2.8);
        double amount = round2(StrictMath.exp(4.1 + 1.2 * gauss(r)));
        return new double[] {
            c1h, c24, round2(c24 * amount * (0.6 + 0.8 * r.nextDouble())), poisson(r, 0.5), poisson(r, 3.2),
            bit(r, 0.35), bit(r, 0.20), bit(r, 0.15), bit(r, 0.45), round4(clamp(1.0 + 1.4 * gauss(r), -10, 10)),
            round4(0.05 + 0.35 * r.nextDouble()), round6(0.0005 + 0.008 * r.nextDouble()),
            r.nextDouble() < 0.35 ? Double.NaN : round2(60 * r.nextDouble()), amount,
            round2(5 + 1500 * r.nextDouble()), bit(r, 0.06), bit(r, 0.03)
        };
    }

    private static double poisson(SplittableRandom r, double lambda) {
        double l = StrictMath.exp(-lambda);
        int k = 0;
        double p = 1;
        do {
            k++;
            p *= r.nextDouble();
        } while (p > l);
        return k - 1;
    }

    /** Box-Muller with StrictMath (bit-reproducible on every JVM). */
    private static double gauss(SplittableRandom r) {
        double u1 = Math.max(r.nextDouble(), 1e-12);
        double u2 = r.nextDouble();
        return StrictMath.sqrt(-2 * StrictMath.log(u1)) * StrictMath.cos(2 * Math.PI * u2);
    }

    private static double bit(SplittableRandom r, double p) {
        return r.nextDouble() < p ? 1.0 : 0.0;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    static double round4(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }

    static double round6(double v) {
        return Math.round(v * 1_000_000.0) / 1_000_000.0;
    }
}
