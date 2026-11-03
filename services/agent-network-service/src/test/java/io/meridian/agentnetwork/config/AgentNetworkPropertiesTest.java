package io.meridian.agentnetwork.config;

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

/** The treasury wallet is tenant data: a tenant's config-service setting wins over the deployment property. */
class AgentNetworkPropertiesTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final UUID TENANT = UUID.fromString("0199c000-0000-7000-8000-000000000001");
    static final UUID OTHER = UUID.fromString("0199c000-0000-7000-8000-000000000002");
    static final UUID TENANT_TREASURY = UUID.fromString("0199c000-0000-7000-8000-0000000000a1");
    static final UUID DEPLOYED = UUID.fromString("0199c000-0000-7000-8000-0000000000d1");

    @AfterEach
    void clean() {
        TenantContext.clear();
        TenantSettings.uninstall();
    }

    @Test
    void treasuryWalletComesFromTheTenantsLedgerSettings() {
        ServiceClient config = new ServiceClient(JSON, new MockEnvironment()) {
            @Override
            public JsonNode get(String service, String path) {
                String values = TENANT.equals(TenantContext.require())
                        ? "{\"agent-network-service\":{\"treasury_wallet_id\":\"" + TENANT_TREASURY + "\"}}" : "{}";
                return JSON.readTree("{\"namespace\":\"ledger\",\"values\":" + values + "}");
            }
        };
        new TenantSettings(config, 30_000).install();
        AgentNetworkProperties props = new AgentNetworkProperties(DEPLOYED, null, null, null, null, null);

        TenantContext.set(TENANT, "u");
        assertThat(props.treasuryWalletId()).isEqualTo(TENANT_TREASURY);
        TenantContext.set(OTHER, "u");
        assertThat(props.treasuryWalletId()).isEqualTo(DEPLOYED);
        assertThat(new AgentNetworkProperties(null, null, null, null, null, null).treasuryWalletId()).isNull();
    }
}
