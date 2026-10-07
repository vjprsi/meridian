package io.meridian.router;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Routing decisions, health hysteresis and break-glass overrides (no I/O). */
class RoutingPolicyTest {
    static final UUID T = UUID.randomUUID();

    static CellDirectory.View cell(String code, String status, boolean healthy) {
        return new CellDirectory.View(UUID.nameUUIDFromBytes(code.getBytes()), code, status, "http://" + code, healthy);
    }

    static Registry.TenantRouting tenant(String mode) {
        return new Registry.TenantRouting(T, "ACTIVE", mode, UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    void homeCellServesAndFrozenTenantsOnlyRead() {
        var a = cell("a", "ACTIVE", true);
        assertThat(RoutingPolicy.decide(tenant("NORMAL"), "POST", a, null, false))
                .isEqualTo(new RoutingPolicy.Route(a, false));
        assertThat(RoutingPolicy.decide(tenant("READ_ONLY"), "GET", a, null, false))
                .isEqualTo(new RoutingPolicy.Route(a, false));
        assertThat(code(RoutingPolicy.decide(tenant("READ_ONLY"), "PATCH", a, null, false))).isEqualTo("tenant_read_only");
        assertThat(code(RoutingPolicy.decide(tenant("BLOCKED"), "GET", a, null, false))).isEqualTo("tenant_blocked");
        assertThat(RoutingPolicy.decide(tenant("NORMAL"), "GET", cell("a", "DRAINING", true), null, false))
                .isInstanceOf(RoutingPolicy.Route.class);
        assertThat(code(RoutingPolicy.decide(tenant("NORMAL"), "DELETE", cell("a", "READ_ONLY", true), null, false)))
                .isEqualTo("cell_read_only");
        assertThat(code(RoutingPolicy.decide(tenant("NORMAL"), "GET", null, null, false))).isEqualTo("tenant_not_placed");
    }

    @Test
    void offlineHomeCellFailsOverToAnUpStandby() {
        var down = cell("a", "OFFLINE", true);
        var b = cell("b", "ACTIVE", true);
        assertThat(RoutingPolicy.decide(tenant("NORMAL"), "POST", down, b, false)).isEqualTo(new RoutingPolicy.Route(b, true));
        assertThat(code(RoutingPolicy.decide(tenant("NORMAL"), "POST", down, null, false))).isEqualTo("cell_unavailable");
        assertThat(code(RoutingPolicy.decide(tenant("NORMAL"), "POST", down, cell("b", "OFFLINE", true), false)))
                .isEqualTo("cell_unavailable");
        // unpromoted warm standby: reads only
        var warm = cell("s", "READ_ONLY", true);
        assertThat(RoutingPolicy.decide(tenant("NORMAL"), "GET", down, warm, false)).isEqualTo(new RoutingPolicy.Route(warm, true));
        assertThat(code(RoutingPolicy.decide(tenant("NORMAL"), "POST", down, warm, false))).isEqualTo("cell_read_only");
        // a frozen tenant stays frozen on the standby too
        assertThat(code(RoutingPolicy.decide(tenant("READ_ONLY"), "POST", down, b, false))).isEqualTo("tenant_read_only");
    }

    @Test
    void healthFailuresOnlyFailOverWithAutoFailover() {
        var sick = cell("a", "ACTIVE", false);
        var b = cell("b", "ACTIVE", true);
        assertThat(RoutingPolicy.decide(tenant("NORMAL"), "GET", sick, b, false)).isEqualTo(new RoutingPolicy.Route(sick, false));
        assertThat(RoutingPolicy.decide(tenant("NORMAL"), "GET", sick, b, true)).isEqualTo(new RoutingPolicy.Route(b, true));
        assertThat(code(RoutingPolicy.decide(tenant("NORMAL"), "GET", sick, cell("b", "ACTIVE", false), true)))
                .isEqualTo("cell_unavailable");
    }

    @Test
    void healthVerdictHasHysteresis() {
        CellDirectory.Health h = new CellDirectory.Health(3, 5);
        assertThat(h.record(false)).isFalse();
        assertThat(h.record(false)).isFalse();
        assertThat(h.record(true)).isFalse();      // a success resets the failure streak
        assertThat(h.record(false)).isFalse();
        assertThat(h.record(false)).isFalse();
        assertThat(h.healthy()).isTrue();
        assertThat(h.record(false)).isTrue();      // third consecutive failure: unhealthy
        assertThat(h.healthy()).isFalse();
        for (int i = 0; i < 4; i++) {
            assertThat(h.record(true)).isFalse();  // flapping back needs five in a row
        }
        assertThat(h.record(false)).isFalse();
        for (int i = 0; i < 4; i++) {
            h.record(true);
        }
        assertThat(h.healthy()).isFalse();
        assertThat(h.record(true)).isTrue();
        assertThat(h.healthy()).isTrue();
    }

    @Test
    void breakGlassOverridesWinOverTheRegistry() {
        RouterProperties props = new RouterProperties(null, null, null, null, null, null, null, null, null, null,
                new RouterProperties.Health(true, Duration.ofSeconds(1), null, null, 2, 2, false), null,
                Map.of("aws-euw1-c01", "offline"), List.of(), null);
        CellDirectory d = new CellDirectory(props);
        UUID id = UUID.randomUUID();
        d.put(new Registry.Cell(id, "aws-euw1-c01", "eu-west-1", "ACTIVE", "http://c01", null));
        assertThat(d.view(id).status()).isEqualTo("OFFLINE");
        assertThat(d.view(id).down(false)).isTrue();
        assertThat(d.probeUrl(d.cell(id).orElseThrow())).isEqualTo("http://c01/actuator/health/readiness");
        d.recordProbe(id, false);
        assertThat(d.recordProbe(id, false)).isTrue();
        assertThat(d.healthy(id)).isFalse();
    }

    private static String code(RoutingPolicy.Decision d) {
        assertThat(d).isInstanceOf(RoutingPolicy.Refuse.class);
        return ((RoutingPolicy.Refuse) d).code();
    }
}
