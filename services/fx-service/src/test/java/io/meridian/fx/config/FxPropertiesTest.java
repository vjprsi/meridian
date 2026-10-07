package io.meridian.fx.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.client.ServiceClient;
import io.meridian.web.config.TenantSettings;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The FX ledger book and accounts are tenant data: a tenant's config-service setting wins over the deployment value. */
class FxPropertiesTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final UUID TENANT = UUID.fromString("0199c000-0000-7000-8000-000000000001");
    static final UUID OTHER = UUID.fromString("0199c000-0000-7000-8000-000000000002");
    static final UUID TENANT_BOOK = UUID.fromString("0199c000-0000-7000-8000-0000000000a1");
    static final UUID TENANT_POS_EUR = UUID.fromString("0199c000-0000-7000-8000-0000000000a2");
    static final UUID TENANT_CLR_USD = UUID.fromString("0199c000-0000-7000-8000-0000000000a3");
    static final UUID DEPLOYED = UUID.fromString("0199c000-0000-7000-8000-0000000000d1");
    static final UUID DEPLOYED_POS_EUR = UUID.fromString("0199c000-0000-7000-8000-0000000000d2");

    @AfterEach
    void clean() {
        TenantContext.clear();
        TenantSettings.uninstall();
    }

    @Test
    void tenantLedgerSettingsWinOverDeploymentValues() {
        ServiceClient config = new ServiceClient(JSON, new MockEnvironment()) {
            @Override
            public JsonNode get(String service, String path) {
                String values = TENANT.equals(TenantContext.require())
                        ? "{\"fx-service\":{\"ledger_id\":\"" + TENANT_BOOK + "\",\"position_accounts\":{\"EUR\":\""
                          + TENANT_POS_EUR + "\"},\"clearing_accounts\":{\"USD\":\"" + TENANT_CLR_USD + "\"}}}" : "{}";
                return JSON.readTree("{\"namespace\":\"ledger\",\"values\":" + values + "}");
            }
        };
        new TenantSettings(config, 30_000).install();
        FxProperties props = new FxProperties(DEPLOYED, null, null, null, Map.of("EUR", DEPLOYED_POS_EUR), null);

        TenantContext.set(TENANT, "u");
        assertThat(props.ledgerId()).isEqualTo(TENANT_BOOK);
        assertThat(props.positionAccount("EUR")).isEqualTo(TENANT_POS_EUR);
        assertThat(props.clearingAccount("USD")).isEqualTo(TENANT_CLR_USD);
        assertThat(props.clearingAccount("EUR")).isNull();

        TenantContext.set(OTHER, "u");
        assertThat(props.ledgerId()).isEqualTo(DEPLOYED);
        assertThat(props.positionAccount("EUR")).isEqualTo(DEPLOYED_POS_EUR);
        assertThat(new FxProperties(null, null, null, null, null, null).ledgerId()).isNull();
    }
}
