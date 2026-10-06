package io.meridian.transactionmonitoring.domain;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Customer AML risk rating (CRR): weighted factors on a 0-1000 scale.
 *
 * <pre>
 * country 25% | product 15% | channel 10% | occupation 15% | behaviour 20% | PEP 15%   (+100 adverse media)
 * LOW < 400 <= MEDIUM < 700 <= HIGH ; PEP is at least HIGH ; PROHIBITED country or sanctioned -> PROHIBITED
 * review: HIGH 1 year (EDD), MEDIUM 2 years, LOW 3 years, PROHIBITED immediately
 * </pre>
 * Behaviour comes from monitoring: TM alerts and alerts escalated to cases in the last 12 months, and the cash share.
 */
public final class RiskRating {
    private RiskRating() {}

    public static final String MODEL_VERSION = "crr-1.0";
    static final Map<String, Double> WEIGHTS = Map.of("country_risk", 0.25, "product_risk", 0.15, "channel_risk", 0.10,
            "occupation_risk", 0.15, "behaviour", 0.20, "pep", 0.15);

    public record Behaviour(int alerts12m, int escalated12m, double cashShare) {
        int score() {
            int s = Math.min(1000, 200 * alerts12m + 300 * escalated12m);
            return Math.min(1000, s + (cashShare > 0.5 ? 150 : 0));
        }
    }

    public record Result(String rating, int score, LocalDate nextReview, Map<String, Object> factors) {}

    public static Result rate(Map<String, Object> inputs, Behaviour behaviour, LocalDate today) {
        Map<String, Object> contributions = new LinkedHashMap<>();
        double total = 0;
        for (String f : new String[] {"country_risk", "product_risk", "channel_risk", "occupation_risk"}) {
            int v = level(inputs.get(f));
            double c = v * WEIGHTS.get(f);
            contributions.put(f, Math.round(c));
            total += c;
        }
        int b = behaviour.score();
        contributions.put("behaviour", Math.round(b * WEIGHTS.get("behaviour")));
        total += b * WEIGHTS.get("behaviour");
        boolean pep = truthy(inputs.get("pep"));
        if (pep) {
            contributions.put("pep", Math.round(1000 * WEIGHTS.get("pep")));
            total += 1000 * WEIGHTS.get("pep");
        }
        if (truthy(inputs.get("adverse_media"))) {
            contributions.put("adverse_media", 100);
            total += 100;
        }
        int score = (int) Math.max(0, Math.min(1000, Math.round(total)));
        String rating = score >= 700 ? "HIGH" : score >= 400 ? "MEDIUM" : "LOW";
        if (pep && !"HIGH".equals(rating)) {
            rating = "HIGH";
        }
        if ("PROHIBITED".equals(str(inputs.get("country_risk"))) || truthy(inputs.get("sanctioned"))) {
            rating = "PROHIBITED";
        }
        Map<String, Object> factors = new LinkedHashMap<>();
        factors.put("inputs", inputs);
        factors.put("weights", WEIGHTS);
        factors.put("contributions", contributions);
        Map<String, Object> beh = new LinkedHashMap<>();
        beh.put("alerts_12m", behaviour.alerts12m());
        beh.put("escalated_12m", behaviour.escalated12m());
        beh.put("cash_share", Math.round(behaviour.cashShare() * 100) / 100.0);
        beh.put("score", b);
        factors.put("behaviour", beh);
        return new Result(rating, score, nextReview(rating, today), factors);
    }

    public static LocalDate nextReview(String rating, LocalDate today) {
        return switch (rating) {
            case "PROHIBITED" -> today;
            case "HIGH" -> today.plusYears(1);
            case "MEDIUM" -> today.plusYears(2);
            default -> today.plusYears(3);
        };
    }

    static int level(Object v) {
        return switch (str(v)) {
            case "PROHIBITED" -> 1000;
            case "HIGH" -> 750;
            case "MEDIUM" -> 400;
            case "LOW" -> 100;
            default -> 250; // unknown: between low and medium until data is complete
        };
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString().trim().toUpperCase(Locale.ROOT);
    }

    private static boolean truthy(Object v) {
        return v instanceof Boolean b ? b : "TRUE".equals(str(v));
    }
}
