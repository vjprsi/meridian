package io.meridian.tenant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.common.error.ApiException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Placement rules for S20: move targets, DR standby candidates, data residency, deployment-model capacity. */
class PlacementRulesTest {
    final JsonMapper json = JsonMapper.builder().build();

    JsonNode cell(String id, String status, String region, String country, String model, int count, int capacity) {
        return json.readTree("""
                {"id":"%s","code":"c-%s","status":"%s","region":"%s",%s"deployment_model":"%s","is_dedicated":false,
                 "tenant_count":%d,"capacity_tenants":%d}""".formatted(id, id, status, region,
                country == null ? "" : "\"country\":\"" + country + "\",", model, count, capacity));
    }

    JsonNode tenant(String residency, String home) {
        return json.readTree(residency == null ? "{\"home_region\":\"" + home + "\"}"
                : "{\"data_residency_country\":\"" + residency + "\",\"home_region\":\"" + home + "\"}");
    }

    @Test
    void moveTargetMustBeAnotherActiveCellWithRoom() {
        JsonNode from = cell("1", "ACTIVE", "eu-west-1", null, "SHARED", 3, 10);
        JsonNode t = tenant(null, "eu-west-1");
        assertThat(code(() -> Placement.requireMoveTarget(t, from, from))).isEqualTo("same_cell");
        assertThat(code(() -> Placement.requireMoveTarget(t, from, cell("2", "DRAINING", "eu-west-1", null, "SHARED", 0, 10))))
                .isEqualTo("cell_not_accepting_tenants");
        assertThat(code(() -> Placement.requireMoveTarget(t, from, cell("2", "OFFLINE", "eu-west-1", null, "SHARED", 0, 10))))
                .isEqualTo("cell_not_accepting_tenants");
        assertThat(code(() -> Placement.requireMoveTarget(t, from, cell("2", "ACTIVE", "eu-west-1", null, "SHARED", 10, 10))))
                .isEqualTo("cell_capacity_exhausted");
        // a dedicated / self-hosted cell holds exactly one tenant whatever capacity_tenants says
        assertThat(code(() -> Placement.requireMoveTarget(t, from, cell("2", "ACTIVE", "eu-west-1", null, "DEDICATED", 1, 200))))
                .isEqualTo("cell_capacity_exhausted");
        assertThat(code(() -> Placement.requireMoveTarget(t, from, cell("2", "ACTIVE", "eu-west-1", null, "SELF_HOSTED", 1, 200))))
                .isEqualTo("cell_capacity_exhausted");
        Placement.requireMoveTarget(t, from, cell("2", "ACTIVE", "us-east-1", null, "DEDICATED", 0, 200));
    }

    @Test
    void residencyUsesTheCellCountryElseTheHomeRegion() {
        JsonNode resident = tenant("KE", "af-south-1");
        assertThat(Placement.residencyAllows(resident, cell("1", "ACTIVE", "af-south-1", null, "SHARED", 0, 1))).isTrue();
        assertThat(Placement.residencyAllows(resident, cell("1", "ACTIVE", "eu-west-1", null, "SHARED", 0, 1))).isFalse();
        assertThat(Placement.residencyAllows(resident, cell("1", "ACTIVE", "ke-nairobi-1", "KE", "SELF_HOSTED", 0, 1))).isTrue();
        assertThat(Placement.residencyAllows(resident, cell("1", "ACTIVE", "af-south-1", "ZA", "SHARED", 0, 1))).isFalse();
        assertThat(Placement.residencyAllows(tenant(null, "eu-west-1"), cell("1", "ACTIVE", "us-east-1", "US", "SHARED", 0, 1)))
                .isTrue();
    }

    @Test
    void standbyMustBeAnotherCellReadyToServe() {
        JsonNode primary = cell("1", "ACTIVE", "eu-west-1", "IE", "SHARED", 1, 10);
        JsonNode t = tenant("IE", "eu-west-1");
        assertThat(code(() -> Placement.requireStandby(t, primary, primary))).isEqualTo("standby_is_home_cell");
        assertThat(code(() -> Placement.requireStandby(t, primary, cell("2", "OFFLINE", "eu-west-2", "IE", "SHARED", 0, 10))))
                .isEqualTo("cell_not_standby_ready");
        assertThat(code(() -> Placement.requireStandby(t, primary, cell("2", "ACTIVE", "eu-central-1", "DE", "SHARED", 0, 10))))
                .isEqualTo("data_residency_violation");
        // a warm standby is READ_ONLY until promoted; a full standby cell does not block (no capacity reserved)
        Placement.requireStandby(t, primary, cell("2", "READ_ONLY", "eu-west-2", "IE", "SHARED", 10, 10));
    }

    @Test
    void cellAndMoveLifecycles() {
        assertThat(Lifecycle.allowed(Lifecycle.CELL, "ACTIVE", "OFFLINE")).isTrue();
        assertThat(Lifecycle.allowed(Lifecycle.CELL, "OFFLINE", "ACTIVE")).isTrue();
        assertThat(Lifecycle.allowed(Lifecycle.CELL, "PROVISIONING", "OFFLINE")).isFalse();
        assertThat(Lifecycle.allowed(Lifecycle.CELL, "DECOMMISSIONED", "ACTIVE")).isFalse();
        assertThat(Lifecycle.allowed(Lifecycle.TENANT_MOVE, "PENDING_APPROVAL", "FROZEN")).isFalse();
        assertThat(Lifecycle.allowed(Lifecycle.TENANT_MOVE, "COPYING", "SWITCHED")).isFalse();
        assertThat(Lifecycle.allowed(Lifecycle.TENANT_MOVE, "SWITCHED", "CANCELLED")).isFalse();
        assertThat(Lifecycle.allowed(Lifecycle.TENANT_MOVE, "COPIED", "CANCELLED")).isTrue();
    }

    private static String code(Runnable r) {
        try {
            r.run();
        } catch (ApiException e) {
            return e.code();
        }
        throw new AssertionError("expected a rule violation");
    }
}
