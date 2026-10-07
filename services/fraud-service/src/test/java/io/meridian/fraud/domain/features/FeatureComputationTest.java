package io.meridian.fraud.domain.features;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Windowed counters, profiles and feature derivation: property tests against brute-force definitions. */
class FeatureComputationTest {
    static final Instant NOW = Instant.parse("2026-10-07T12:34:56Z");

    record Ev(long secondsAgo, double amount) {}

    @Provide
    Arbitrary<List<Ev>> events() {
        // within 9 days back, biased towards the edges of the windows
        Arbitrary<Long> ago = Arbitraries.oneOf(
                Arbitraries.longs().between(0, 9 * 86_400L),
                Arbitraries.longs().between(3_000, 3_900),
                Arbitraries.longs().between(82_000, 87_000),
                Arbitraries.longs().between(600_000, 606_000));
        Arbitrary<Ev> ev = net.jqwik.api.Combinators.combine(ago, Arbitraries.doubles().between(0, 5000).ofScale(2))
                .as(Ev::new);
        return ev.list().ofMaxSize(200);
    }

    @Property(tries = 300)
    void windowCountsEqualTheBruteForceDefinition(@ForAll("events") List<Ev> events,
                                                  @ForAll @IntRange(min = 0, max = 3599) int nowOffset) {
        Instant now = NOW.plusSeconds(nowOffset);
        WindowCounter c = new WindowCounter();
        for (Ev e : events) {
            c.add(now.minusSeconds(e.secondsAgo()), e.amount());
        }
        for (Buckets.Window w : Buckets.Window.values()) {
            double count = 0;
            double amount = 0;
            for (Ev e : events) {
                Instant t = now.minusSeconds(e.secondsAgo());
                // definition: same-or-later bucket than floor(now) - W + bucket
                if (!Buckets.floor(t, w.bucket).isBefore(Buckets.floor(now, w.bucket).minus(w.length).plus(w.bucket))) {
                    count++;
                    amount += e.amount();
                }
            }
            double[] got = c.window(w, now);
            assertThat(got[0]).as(w.suffix).isEqualTo(count);
            assertThat(got[1]).as(w.suffix).isCloseTo(amount, offset(1e-6));
        }
    }

    @Property(tries = 200)
    void windowsSpanBetweenLengthMinusBucketAndLength(@ForAll @IntRange(min = 0, max = 86_400 * 8) int secondsAgo,
                                                      @ForAll @IntRange(min = 0, max = 86_399) int nowOffset) {
        Instant now = NOW.plusSeconds(nowOffset);
        Instant t = now.minusSeconds(secondsAgo);
        for (Buckets.Window w : Buckets.Window.values()) {
            boolean in = w.contains(t, now);
            if (secondsAgo <= w.length.minus(w.bucket).toSeconds()) {
                assertThat(in).as("%s: %ss ago must be inside", w.suffix, secondsAgo).isTrue();
            }
            if (secondsAgo > w.length.toSeconds()) {
                assertThat(in).as("%s: %ss ago must be outside", w.suffix, secondsAgo).isFalse();
            }
        }
    }

    @Property(tries = 100)
    void pruningNeverChangesTheWindows(@ForAll("events") List<Ev> events) {
        WindowCounter c = new WindowCounter();
        events.forEach(e -> c.add(NOW.minusSeconds(e.secondsAgo()), e.amount()));
        Map<Buckets.Window, double[]> before = new HashMap<>();
        for (Buckets.Window w : Buckets.Window.values()) {
            before.put(w, c.window(w, NOW));
        }
        c.prune(NOW);
        for (Buckets.Window w : Buckets.Window.values()) {
            assertThat(c.window(w, NOW)).containsExactly(before.get(w));
        }
        // bounded: at most 2 h of 5-minute buckets (+1 partial) and 8 d of hourly buckets (+1)
        assertThat(c.buckets()).isLessThanOrEqualTo(25 + 193);
    }

    @Property(tries = 200)
    void profileMeanStaysWithinTheDataAndVarianceIsNonNegative(
            @ForAll @Size(min = 1, max = 300) List<@DoubleRange(min = 0, max = 100_000) Double> amounts) {
        Profile p = Profile.EMPTY;
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        Instant t = NOW;
        for (double a : amounts) {
            p = p.update(a, t);
            t = t.plusSeconds(3_600);
            lo = Math.min(lo, a);
            hi = Math.max(hi, a);
        }
        assertThat(p.count()).isEqualTo(amounts.size());
        assertThat(p.mean()).isBetween(lo - 1e-6, hi + 1e-6);
        assertThat(p.var()).isGreaterThanOrEqualTo(0.0);
        assertThat(p.zScore(1e9)).isBetween(-10.0, 10.0);
        assertThat(p.hourDeviation(NOW.plusSeconds(7_200))).isBetween(0.0, 1.0);
    }

    @Test
    void profileIsAPlainMeanForTheFirstEventsAndForgetsLater() {
        Profile p = Profile.EMPTY.update(10.0, NOW).update(20.0, NOW).update(30.0, NOW);
        assertThat(p.mean()).isCloseTo(20.0, offset(1e-12));
        // population variance of 10, 20, 30 = 66.67 (West's update with a = 1/n)
        assertThat(p.var()).isCloseTo(200.0 / 3.0, offset(1e-9));
        assertThat(p.zScore(40)).isZero(); // fewer than MIN_HISTORY events
        for (int i = 0; i < 500; i++) {
            p = p.update(1000.0, NOW);
        }
        assertThat(p.mean()).isCloseTo(1000.0, offset(0.1));
    }

    @Test
    void hourDeviationIsHighAtTheOppositeHour() {
        Profile p = Profile.EMPTY;
        Instant nine = Instant.parse("2026-10-01T09:00:00Z");
        for (int i = 0; i < 20; i++) {
            p = p.update(50.0, nine.plus(Duration.ofDays(i)));
        }
        assertThat(p.hourDeviation(Instant.parse("2026-10-07T09:00:00Z"))).isCloseTo(0.0, offset(1e-6));
        assertThat(p.hourDeviation(Instant.parse("2026-10-07T21:00:00Z"))).isCloseTo(1.0, offset(1e-6));
        assertThat(p.hourDeviation(Instant.parse("2026-10-07T15:00:00Z"))).isCloseTo(0.5, offset(1e-6));
    }

    @Test
    void storeFeaturesAreMissingForAbsentEntitiesAndDerivedOtherwise() {
        Map<String, double[]> windows = new HashMap<>();
        windows.put("CUSTOMER:1h", new double[] {3, 300});
        windows.put("CUSTOMER:24h", new double[] {5, 500});
        windows.put("BENEFICIARY:7d", new double[] {9, 0});
        Profile customer = new Profile(10, 100, 400, 0, 0, NOW.minus(Duration.ofDays(30)), NOW, 0, 0);
        Profile merchant = new Profile(800, 0, 0, 0, 0, NOW, NOW, 3, 5);
        Map<String, Boolean> links = new HashMap<>();
        links.put("CUSTOMER_BENEFICIARY", false);
        links.put("CUSTOMER_DEVICE", true);
        StoreFeatures.Snapshot s = new StoreFeatures.Snapshot(NOW, 160.0,
                Map.of("CUSTOMER", "c", "MERCHANT", "m", "BENEFICIARY", "b", "DEVICE", "d"), windows, customer,
                NOW.minus(Duration.ofDays(30)), merchant, null, links);
        Map<String, Double> f = StoreFeatures.compute(s);
        assertThat(f.get("customer_txn_count_1h")).isEqualTo(3.0);
        assertThat(f.get("customer_txn_amount_24h")).isEqualTo(500.0);
        assertThat(f.get("customer_txn_count_7d")).isEqualTo(0.0);
        assertThat(f.get("card_txn_count_1h")).isNaN();                 // no card in the request
        assertThat(f.get("beneficiary_txn_count_7d")).isEqualTo(9.0);
        assertThat(f.get("new_payee")).isEqualTo(1.0);
        assertThat(f.get("new_device_for_customer")).isEqualTo(0.0);
        assertThat(f.get("new_country_for_customer")).isNaN();
        assertThat(f.get("amount_zscore")).isCloseTo(3.0, offset(1e-12));  // (160 - 100) / sqrt(400)
        assertThat(f.get("customer_tenure_days")).isCloseTo(30.0, offset(1e-9));
        assertThat(f.get("beneficiary_age_days")).isEqualTo(0.0);        // never seen before
        assertThat(f.get("merchant_risk")).isCloseTo((3 + 0.2) / (800 + 200.0), offset(1e-12));
        assertThat(f.get("txn_amount")).isEqualTo(160.0);
        assertThat(f.get("hour_of_day")).isCloseTo(12 + 34 / 60.0 + 56 / 3600.0, offset(1e-9));
        assertThat(f.keySet()).isSubsetOf(FeatureCatalog.names());
    }

    @Test
    void everyStoreFeatureIsCatalogued() {
        StoreFeatures.Snapshot empty = new StoreFeatures.Snapshot(NOW, null, Map.of(), Map.of(), null, null, null, null, Map.of());
        assertThat(FeatureCatalog.names()).containsAll(StoreFeatures.compute(empty).keySet());
        assertThat(FeatureCatalog.all()).allSatisfy(d -> {
            assertThat(d.name()).matches("[a-z][a-z0-9_]{1,127}");
            assertThat(d.entity()).isIn("CUSTOMER", "ACCOUNT", "CARD", "DEVICE", "MERCHANT", "BENEFICIARY", "TRANSACTION");
            assertThat(d.definition()).isNotEmpty();
        });
        List<String> names = new ArrayList<>();
        FeatureCatalog.all().forEach(d -> names.add(d.name()));
        assertThat(names).doesNotHaveDuplicates();
    }

    // ------------------------------------------------------------------ event mapping

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void paymentEventsMapToCustomerAccountBeneficiaryAndLinks() {
        var p = JSON.readTree("""
                {"id":"p1","direction":"OUTBOUND","customer_id":"c1","debtor_account_id":"a1","amount":"250.00",
                 "currency":"KES","creditor_account_number":"0012 3456-78","creditor_agent_bic":"abcdkenx",
                 "creditor_country":"ug","created_at":"2026-10-07T10:00:00Z"}""");
        FeatureEvent e = FeatureEvent.from("ev1", FeatureEvent.PAYMENT_INITIATED, null, p).orElseThrow();
        String beneficiary = FeatureEvent.Keys.beneficiary(null, "00123456 78", "ABCDKENX", null, null);
        assertThat(e.counters()).containsEntry("CUSTOMER", "c1").containsEntry("ACCOUNT", "a1")
                .containsEntry("BENEFICIARY", beneficiary);
        assertThat(beneficiary).startsWith("acct:").doesNotContain("0012");
        assertThat(e.links()).contains(new FeatureEvent.Link("CUSTOMER_BENEFICIARY", "c1", beneficiary),
                new FeatureEvent.Link("CUSTOMER_COUNTRY", "c1", "UG"));
        assertThat(e.amount()).isEqualByComparingTo("250.00");
        assertThat(e.occurredAt()).isEqualTo(Instant.parse("2026-10-07T10:00:00Z"));
        var inbound = JSON.readTree("{\"direction\":\"INBOUND\",\"customer_id\":\"c1\",\"amount\":\"1\",\"currency\":\"KES\"}");
        assertThat(FeatureEvent.from("ev2", FeatureEvent.PAYMENT_INITIATED, NOW, inbound)).isEmpty();
        assertThat(FeatureEvent.from("ev3", "payments.payment.settled", NOW, p)).isEmpty();
    }

    @Test
    void cardWalletAndEvaluationEventsMap() {
        var card = JSON.readTree("""
                {"card_id":"k1","customer_id":"c1","merchant_id":"M-9","merchant_country":"gb","billing_amount":"12.5",
                 "billing_currency":"KES","transaction_amount":"0.1","transaction_currency":"GBP"}""");
        FeatureEvent c = FeatureEvent.from("e1", FeatureEvent.CARD_DECLINED, NOW, card).orElseThrow();
        assertThat(c.counters()).containsKeys("CUSTOMER", "CARD", "MERCHANT");
        assertThat(c.currency()).isEqualTo("KES");
        assertThat(c.occurredAt()).isEqualTo(NOW); // ce_time when the payload has no created_at
        assertThat(c.links()).contains(new FeatureEvent.Link("CARD_MERCHANT", "k1", "M-9"), new FeatureEvent.Link("CARD_COUNTRY", "k1", "GB"));

        var wallet = JSON.readTree("{\"payer_wallet_id\":\"w1\",\"payee_msisdn\":\"+254 712 000111\",\"amount\":\"5\",\"currency\":\"KES\"}");
        FeatureEvent w = FeatureEvent.from("e2", FeatureEvent.WALLET_INITIATED, NOW, wallet).orElseThrow();
        assertThat(w.counters().get("ACCOUNT")).isEqualTo("w1");
        assertThat(w.counters().get("BENEFICIARY")).isEqualTo(FeatureEvent.Keys.beneficiary(null, null, null, null, "254712000111"));
        assertThat(w.links()).contains(new FeatureEvent.Link("ACCOUNT_BENEFICIARY", "w1", w.counters().get("BENEFICIARY")));

        var login = JSON.readTree("{\"subject_type\":\"LOGIN\",\"customer_id\":\"c1\",\"device_fingerprint_id\":\"d1\"}");
        FeatureEvent l = FeatureEvent.from("e3", FeatureEvent.FRAUD_EVALUATED, NOW, login).orElseThrow();
        assertThat(l.counters()).containsEntry("CUSTOMER_LOGIN", "c1").containsEntry("DEVICE", "d1");
        assertThat(l.amount()).isNull();
        assertThat(l.currency()).isEqualTo(FeatureEvent.NO_CURRENCY);
        assertThat(l.links()).containsExactly(new FeatureEvent.Link("CUSTOMER_DEVICE", "c1", "d1"));
    }
}
