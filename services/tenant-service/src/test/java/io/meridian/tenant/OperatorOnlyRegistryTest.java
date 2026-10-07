package io.meridian.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.tenant.api.generated.CellsApi;
import io.meridian.tenant.api.generated.TenantMovesApi;
import io.meridian.tenant.api.generated.TenantsApi;
import io.meridian.web.security.RequiredScope;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * SEC-15 stays intact with S20: every operation of the tenant registry (cells, tenants, placements, routing view,
 * tenant moves) requires the exact {@code platform:operator} scope, which only the platform-operator tenant's users
 * and internal service tokens can hold. The cell router's reads (routing view, cells) take its own narrow
 * {@code platform:routing-read} instead, under the same operator-tenant rule.
 */
class OperatorOnlyRegistryTest {
    /** Registry reads of the cell router (meridian-cell-router client, scope platform:routing-read). */
    static final java.util.Set<String> ROUTER_READS = java.util.Set.of("tenantsRouting", "cellsList", "cellsGet");

    @Test
    void everyRegistryOperationIsOperatorOnly() {
        List<String> checked = new ArrayList<>();
        for (Class<?> api : new Class<?>[] {CellsApi.class, TenantsApi.class, TenantMovesApi.class}) {
            for (Method m : api.getDeclaredMethods()) {
                if (!m.isAnnotationPresent(RequestMapping.class)) {
                    continue;
                }
                RequiredScope scope = m.getAnnotation(RequiredScope.class);
                assertThat(scope).as(api.getSimpleName() + "." + m.getName()).isNotNull();
                if (ROUTER_READS.contains(m.getName())) {
                    // the cell router's own client: the narrow platform:routing-read, platform:operator still accepted
                    assertThat(scope.value()).as(m.getName()).isEqualTo("platform:routing-read");
                    assertThat(scope.anyOf()).as(m.getName()).containsExactly("platform:operator");
                } else {
                    assertThat(scope.value()).as(api.getSimpleName() + "." + m.getName()).isEqualTo("platform:operator");
                    assertThat(scope.anyOf()).as(m.getName()).isEmpty();
                }
                checked.add(m.getName());
            }
        }
        assertThat(checked).containsAll(ROUTER_READS);
        assertThat(checked).contains("tenantsRouting", "tenantsSetStandby", "cellsOffline", "cellsReadOnly",
                "cellsOnline", "tenantMovesCreate", "tenantMovesFreeze", "tenantMovesSwitch", "tenantMovesList",
                "tenantMovesGet", "tenantsPlacements");
    }
}
