package io.meridian.crm.application;

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

/** Redress ledger / funding account: the redress's own value, then the tenant's config-service setting, then the deployment. */
class RedressLedgerSettingsTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final UUID TENANT = UUID.fromString("0199c000-0000-7000-8000-000000000001");
    static final UUID OTHER = UUID.fromString("0199c000-0000-7000-8000-000000000002");
    static final String TENANT_BOOK = "0199c000-0000-7000-8000-0000000000a1";
    static final String TENANT_FUNDING = "0199c000-0000-7000-8000-0000000000a2";
    static final String DEPLOYED_BOOK = "0199c000-0000-7000-8000-0000000000d1";
    static final String OWN_BOOK = "0199c000-0000-7000-8000-0000000000e1";

    @AfterEach
    void clean() {
        TenantContext.clear();
        TenantSettings.uninstall();
    }

    @Test
    void tenantSettingWinsOverDeploymentButNotOverTheRedressOwnValue() {
        ServiceClient config = new ServiceClient(JSON, new MockEnvironment()) {
            @Override
            public JsonNode get(String service, String path) {
                String values = TENANT.equals(TenantContext.require())
                        ? "{\"crm-service\":{\"redress\":{\"ledger_id\":\"" + TENANT_BOOK + "\",\"funding_account_id\":\""
                          + TENANT_FUNDING + "\"}}}" : "{}";
                return JSON.readTree("{\"namespace\":\"ledger\",\"values\":" + values + "}");
            }
        };
        new TenantSettings(config, 30_000).install();

        TenantContext.set(TENANT, "u");
        assertThat(RedressService.resolve(null, "redress.ledger_id", DEPLOYED_BOOK)).isEqualTo(TENANT_BOOK);
        assertThat(RedressService.resolve(null, "redress.funding_account_id", "")).isEqualTo(TENANT_FUNDING);
        assertThat(RedressService.resolve(OWN_BOOK, "redress.ledger_id", DEPLOYED_BOOK)).isEqualTo(OWN_BOOK);

        TenantContext.set(OTHER, "u");
        assertThat(RedressService.resolve(null, "redress.ledger_id", DEPLOYED_BOOK)).isEqualTo(DEPLOYED_BOOK);
        assertThat(RedressService.resolve(null, "redress.funding_account_id", "")).isNull();
    }
}
