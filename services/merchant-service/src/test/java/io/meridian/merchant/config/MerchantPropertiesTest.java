package io.meridian.merchant.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.client.ServiceClient;
import io.meridian.web.config.TenantSettings;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The merchant fee income account: a tenant's config-service setting (namespace ledger) wins over the deployment property. */
class MerchantPropertiesTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final UUID TENANT = UUID.fromString("0199c000-0000-7000-8000-000000000001");
    static final UUID OTHER = UUID.fromString("0199c000-0000-7000-8000-000000000002");
    static final UUID TENANT_VALUE = UUID.fromString("0199c000-0000-7000-8000-0000000000a1");
    static final UUID DEPLOYED = UUID.fromString("0199c000-0000-7000-8000-0000000000d1");

    @AfterEach
    void clean() {
        TenantContext.clear();
        TenantSettings.uninstall();
    }

    @Test
    void tenantSettingWinsOverDeploymentValue() {
        ServiceClient config = new ServiceClient(JSON, new MockEnvironment()) {
            @Override
            public JsonNode get(String service, String path) {
                assertThat(service).isEqualTo("config-service");
                String values = TENANT.equals(TenantContext.require())
                        ? "{\"merchant-service\":{\"fee_income_account_id\":\"" + TENANT_VALUE + "\"}}" : "{}";
                return JSON.readTree("{\"namespace\":\"ledger\",\"values\":" + values + "}");
            }
        };
        new TenantSettings(config, 30_000).install();
        MerchantProperties props = new MerchantProperties(null, DEPLOYED, null);

        TenantContext.set(TENANT, "u");
        assertThat(props.feeIncomeAccountId()).isEqualTo(TENANT_VALUE);
        TenantContext.set(OTHER, "u");
        assertThat(props.feeIncomeAccountId()).isEqualTo(DEPLOYED);
        assertThat(new MerchantProperties(null, null, null).feeIncomeAccountId()).isNull();
    }
}
