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
 * and internal service tokens (the cell router) can hold.
 */
class OperatorOnlyRegistryTest {

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
                assertThat(scope.value()).as(api.getSimpleName() + "." + m.getName()).isEqualTo("platform:operator");
                checked.add(m.getName());
            }
        }
        assertThat(checked).contains("tenantsRouting", "tenantsSetStandby", "cellsOffline", "cellsReadOnly",
                "cellsOnline", "tenantMovesCreate", "tenantMovesFreeze", "tenantMovesSwitch", "tenantMovesList",
                "tenantMovesGet", "tenantsPlacements");
    }
}
