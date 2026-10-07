package io.meridian.routing.config;

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

/** The ledger book and treasury funding account are tenant data: config-service wins over the deployment value. */
class RoutingPropertiesTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final UUID TENANT = UUID.fromString("0199c000-0000-7000-8000-000000000001");
    static final UUID OTHER = UUID.fromString("0199c000-0000-7000-8000-000000000002");
    static final UUID TENANT_BOOK = UUID.fromString("0199c000-0000-7000-8000-0000000000a1");
    static final UUID TENANT_FUNDING = UUID.fromString("0199c000-0000-7000-8000-0000000000a2");
    static final UUID DEPLOYED_BOOK = UUID.fromString("0199c000-0000-7000-8000-0000000000d1");
    static final UUID DEPLOYED_FUNDING = UUID.fromString("0199c000-0000-7000-8000-0000000000d2");

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
                        ? "{\"routing-service\":{\"ledger_id\":\"" + TENANT_BOOK + "\",\"gl\":{\"treasury_funding\":\""
                          + TENANT_FUNDING + "\"}}}" : "{}";
                return JSON.readTree("{\"namespace\":\"ledger\",\"values\":" + values + "}");
            }
        };
        new TenantSettings(config, 30_000).install();
        RoutingProperties props = new RoutingProperties(DEPLOYED_BOOK, null, null, null, null,
                new RoutingProperties.Gl(DEPLOYED_FUNDING));

        TenantContext.set(TENANT, "u");
        assertThat(props.ledgerId()).isEqualTo(TENANT_BOOK);
        assertThat(props.gl().treasuryFunding()).isEqualTo(TENANT_FUNDING);

        TenantContext.set(OTHER, "u");
        assertThat(props.ledgerId()).isEqualTo(DEPLOYED_BOOK);
        assertThat(props.gl().treasuryFunding()).isEqualTo(DEPLOYED_FUNDING);
        RoutingProperties unset = new RoutingProperties(null, null, null, null, null, null);
        assertThat(unset.ledgerId()).isNull();
        assertThat(unset.gl().treasuryFunding()).isNull();
    }
}
