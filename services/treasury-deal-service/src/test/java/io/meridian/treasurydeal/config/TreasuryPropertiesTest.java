package io.meridian.treasurydeal.config;

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

/** The treasury ledger book and accounts are tenant data: a tenant's config-service setting wins over the deployment value. */
class TreasuryPropertiesTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final UUID TENANT = UUID.fromString("0199c000-0000-7000-8000-000000000001");
    static final UUID OTHER = UUID.fromString("0199c000-0000-7000-8000-000000000002");
    static final UUID TENANT_BOOK = UUID.fromString("0199c000-0000-7000-8000-0000000000a1");
    static final UUID TENANT_PLACEMENTS = UUID.fromString("0199c000-0000-7000-8000-0000000000a2");
    static final UUID DEPLOYED_BOOK = UUID.fromString("0199c000-0000-7000-8000-0000000000d1");
    static final UUID DEPLOYED_PLACEMENTS = UUID.fromString("0199c000-0000-7000-8000-0000000000d2");
    static final UUID DEPLOYED_NOSTRO = UUID.fromString("0199c000-0000-7000-8000-0000000000d3");

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
                        ? "{\"treasury-deal-service\":{\"ledger_id\":\"" + TENANT_BOOK
                          + "\",\"accounts\":{\"EUR\":{\"mm_placements\":\"" + TENANT_PLACEMENTS + "\"}}}}" : "{}";
                return JSON.readTree("{\"namespace\":\"ledger\",\"values\":" + values + "}");
            }
        };
        new TenantSettings(config, 30_000).install();
        TreasuryProperties props = new TreasuryProperties(DEPLOYED_BOOK, null,
                Map.of("EUR", Map.of("mm-placements", DEPLOYED_PLACEMENTS, "nostro", DEPLOYED_NOSTRO)));

        TenantContext.set(TENANT, "u");
        assertThat(props.ledgerId()).isEqualTo(TENANT_BOOK);
        assertThat(props.account("EUR", "mm-placements")).isEqualTo(TENANT_PLACEMENTS);
        assertThat(props.account("EUR", "nostro")).isEqualTo(DEPLOYED_NOSTRO); // not set for the tenant
        assertThat(props.account("USD", "nostro")).isNull();

        TenantContext.set(OTHER, "u");
        assertThat(props.ledgerId()).isEqualTo(DEPLOYED_BOOK);
        assertThat(props.account("EUR", "mm-placements")).isEqualTo(DEPLOYED_PLACEMENTS);
        assertThat(new TreasuryProperties(null, null, null).ledgerId()).isNull();
    }
}
