package io.meridian.cardclearing.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.cardclearing.infrastructure.Clients;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.client.ServiceClient;
import io.meridian.web.config.TenantSettings;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The clearing ledger book and accounts are tenant data: config-service wins; nothing configured means no posting. */
class ClearingPropertiesTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final UUID TENANT = UUID.fromString("0199c000-0000-7000-8000-000000000001");
    static final UUID OTHER = UUID.fromString("0199c000-0000-7000-8000-000000000002");
    static final UUID TENANT_BOOK = UUID.fromString("0199c000-0000-7000-8000-0000000000a1");
    static final UUID TENANT_VISA = UUID.fromString("0199c000-0000-7000-8000-0000000000a2");
    static final UUID TENANT_FEE_INCOME = UUID.fromString("0199c000-0000-7000-8000-0000000000a3");
    static final UUID DEPLOYED_BOOK = UUID.fromString("0199c000-0000-7000-8000-0000000000d1");
    static final UUID DEPLOYED_VISA = UUID.fromString("0199c000-0000-7000-8000-0000000000d2");
    static final UUID DEPLOYED_MC = UUID.fromString("0199c000-0000-7000-8000-0000000000d3");

    @AfterEach
    void clean() {
        TenantContext.clear();
        TenantSettings.uninstall();
    }

    static ServiceClient config() {
        return new ServiceClient(JSON, new MockEnvironment()) {
            @Override
            public JsonNode get(String service, String path) {
                assertThat(service).isEqualTo("config-service");
                String values = TENANT.equals(TenantContext.require())
                        ? "{\"card-clearing-service\":{\"ledger_id\":\"" + TENANT_BOOK + "\",\"clearing\":{"
                          + "\"settlement_accounts\":{\"VISA\":\"" + TENANT_VISA + "\"},"
                          + "\"fee_income_account\":\"" + TENANT_FEE_INCOME + "\"}}}" : "{}";
                return JSON.readTree("{\"namespace\":\"ledger\",\"values\":" + values + "}");
            }

            @Override
            public JsonNode post(String service, String path, Object body, String key) {
                throw new AssertionError("must not post to " + service + path);
            }
        };
    }

    @Test
    void tenantLedgerSettingsWinOverDeploymentValues() {
        new TenantSettings(config(), 30_000).install();
        ClearingProperties props = new ClearingProperties(DEPLOYED_BOOK, new ClearingProperties.Clearing(
                Map.of("VISA", DEPLOYED_VISA, "MASTERCARD", DEPLOYED_MC), null, null, null, null, null, null, null));

        TenantContext.set(TENANT, "u");
        assertThat(props.ledgerId()).isEqualTo(TENANT_BOOK);
        assertThat(props.clearing().settlementAccount("VISA")).isEqualTo(TENANT_VISA);
        assertThat(props.clearing().settlementAccount("MASTERCARD")).isEqualTo(DEPLOYED_MC);
        assertThat(props.clearing().feeIncomeAccount()).isEqualTo(TENANT_FEE_INCOME);

        TenantContext.set(OTHER, "u");
        assertThat(props.ledgerId()).isEqualTo(DEPLOYED_BOOK);
        assertThat(props.clearing().settlementAccount("VISA")).isEqualTo(DEPLOYED_VISA);
        assertThat(props.clearing().feeIncomeAccount()).isNull();
    }

    @Test
    void withoutTenantOrDeploymentLedgerNothingIsPosted() {
        ServiceClient client = config();
        new TenantSettings(client, 30_000).install();
        TenantContext.set(OTHER, "u");
        ClearingProperties props = new ClearingProperties(null, null);
        Clients clients = new Clients(client, JSON);

        assertThatThrownBy(() -> clients.post(props.ledgerId(), "FEE", "fee", "EUR", "CARD_FEE", UUID.randomUUID(), null,
                List.of(new Clients.Leg(UUID.randomUUID(), "DEBIT", BigDecimal.ONE, "fee"),
                        new Clients.Leg(props.clearing().feeIncomeAccount(), "CREDIT", BigDecimal.ONE, "fee")), "k"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(422);
                    assertThat(e.code()).isEqualTo("ledger_not_configured");
                });
    }
}
