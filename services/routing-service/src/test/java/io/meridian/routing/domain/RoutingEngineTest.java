package io.meridian.routing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RoutingEngineTest {
    static final UUID FPS = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID BACS = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final UUID CHAPS = UUID.fromString("00000000-0000-0000-0000-000000000003");

    static Map<UUID, RoutingEngine.Rail> rails(String fpsStatus) {
        Map<UUID, RoutingEngine.Rail> m = new LinkedHashMap<>();
        m.put(FPS, new RoutingEngine.Rail(FPS, "FPS", "INSTANT", fpsStatus, Set.of("GBP"), "GB",
                new BigDecimal("1000000"), new BigDecimal("0.10"), 1, true, new BigDecimal("0.999"), 2000));
        m.put(BACS, new RoutingEngine.Rail(BACS, "BACS", "BATCH", "ACTIVE", Set.of("GBP"), "GB", null,
                new BigDecimal("0.02"), 4320, false, new BigDecimal("0.995"), null));
        m.put(CHAPS, new RoutingEngine.Rail(CHAPS, "CHAPS", "RTGS", "ACTIVE", Set.of("GBP"), "GB", null,
                new BigDecimal("3.50"), 60, false, new BigDecimal("0.9999"), null));
        return m;
    }

    static RoutingEngine.Facts facts(String amount) {
        return new RoutingEngine.Facts("DOMESTIC", "OUTBOUND", new BigDecimal(amount), "GBP", "GB", "NORMAL",
                LocalDate.parse("2026-10-06"));
    }

    static RoutingEngine.Rule rule(String strategy, Map<String, Object> cond, int prio, UUID fallback) {
        return new RoutingEngine.Rule(UUID.randomUUID(), strategy, prio, cond, strategy, List.of(FPS, BACS, CHAPS),
                fallback, Map.of(), true, null, null);
    }

    @Test
    void strategiesPickTheExpectedRail() {
        assertThat(RoutingEngine.route(facts("100"), List.of(rule("CHEAPEST", Map.of(), 1, null)), rails("ACTIVE"))
                .rail().code()).isEqualTo("BACS");
        assertThat(RoutingEngine.route(facts("100"), List.of(rule("FASTEST", Map.of(), 1, null)), rails("ACTIVE"))
                .rail().code()).isEqualTo("FPS");
        assertThat(RoutingEngine.route(facts("100"), List.of(rule("MOST_RELIABLE", Map.of(), 1, null)), rails("ACTIVE"))
                .rail().code()).isEqualTo("CHAPS");
        assertThat(RoutingEngine.route(facts("100"), List.of(rule("FIXED", Map.of(), 1, null)), rails("ACTIVE"))
                .rail().code()).isEqualTo("FPS");
        RoutingEngine.Decision balanced = RoutingEngine.route(facts("100"),
                List.of(new RoutingEngine.Rule(UUID.randomUUID(), "b", 1, Map.of(), "BALANCED", List.of(FPS, BACS, CHAPS),
                        null, Map.of("cost", BigDecimal.ZERO, "speed", BigDecimal.ONE, "success", BigDecimal.ZERO), true,
                        null, null)), rails("ACTIVE"));
        assertThat(balanced.rail().code()).isEqualTo("FPS");
    }

    @Test
    void amountAboveSchemeMaximumAndSuspensionFallBack() {
        RoutingEngine.Decision d = RoutingEngine.route(facts("2000000"), List.of(rule("FASTEST", Map.of(), 1, null)),
                rails("ACTIVE"));
        assertThat(d.rail().code()).isNotEqualTo("FPS");
        RoutingEngine.Candidate fps = d.evaluations().getFirst().candidates().stream()
                .filter(c -> c.code().equals("FPS")).findFirst().orElseThrow();
        assertThat(fps.eligible()).isFalse();
        assertThat(fps.reasons()).anyMatch(r -> r.contains("scheme maximum"));

        RoutingEngine.Rule fpsOnly = new RoutingEngine.Rule(UUID.randomUUID(), "fps only", 1, Map.of(), "FIXED",
                List.of(FPS), CHAPS, Map.of(), true, null, null);
        RoutingEngine.Decision fb = RoutingEngine.route(facts("10"), List.of(fpsOnly), rails("SUSPENDED"));
        assertThat(fb.rail().code()).isEqualTo("CHAPS");
        assertThat(fb.evaluations().getFirst().reasons()).anyMatch(r -> r.contains("fallback"));
    }

    @Test
    void degradedRailsRankAfterActiveOnes() {
        RoutingEngine.Decision d = RoutingEngine.route(facts("10"), List.of(rule("FASTEST", Map.of(), 1, null)),
                rails("DEGRADED"));
        assertThat(d.rail().code()).isNotEqualTo("FPS");
        RoutingEngine.Rule fpsOnly = new RoutingEngine.Rule(UUID.randomUUID(), "fps only", 1, Map.of(), "FIXED",
                List.of(FPS), null, Map.of(), true, null, null);
        assertThat(RoutingEngine.route(facts("10"), List.of(fpsOnly), rails("DEGRADED")).rail().code()).isEqualTo("FPS");
    }

    @Test
    void conditionsPriorityValidityAndDefaultRoute() {
        RoutingEngine.Rule highValue = rule("MOST_RELIABLE", Map.of("min_amount", "250000"), 10, null);
        RoutingEngine.Rule cheap = rule("CHEAPEST", Map.of("payment_types", List.of("DOMESTIC"), "currencies", "GBP"), 20, null);
        RoutingEngine.Rule expired = new RoutingEngine.Rule(UUID.randomUUID(), "expired", 1, Map.of(), "FASTEST",
                List.of(FPS), null, Map.of(), true, null, LocalDate.parse("2026-01-01"));
        RoutingEngine.Decision low = RoutingEngine.route(facts("100"), List.of(cheap, highValue, expired), rails("ACTIVE"));
        assertThat(low.rail().code()).isEqualTo("BACS");
        assertThat(low.evaluations()).hasSize(3);
        assertThat(low.evaluations().get(0).reasons()).anyMatch(r -> r.contains("expired"));
        assertThat(RoutingEngine.route(facts("300000"), List.of(cheap, highValue), rails("ACTIVE")).rail().code())
                .isEqualTo("CHAPS");

        RoutingEngine.Facts usd = new RoutingEngine.Facts("DOMESTIC", "OUTBOUND", BigDecimal.TEN, "USD", "US", null, null);
        RoutingEngine.Decision none = RoutingEngine.route(usd, List.of(cheap), rails("ACTIVE"));
        assertThat(none.routable()).isFalse();
        assertThat(none.reason()).contains("USD");
        // no rule at all: cheapest eligible rail
        assertThat(RoutingEngine.route(facts("100"), List.of(), rails("ACTIVE")).rail().code()).isEqualTo("BACS");
    }
}
