package io.meridian.carddispute.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.carddispute.infrastructure.Downstream;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.client.ServiceClient;
import io.meridian.web.config.TenantSettings;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The dispute ledger book and accounts are tenant data: config-service wins; nothing configured means no posting. */
class DisputePropertiesTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final UUID TENANT = UUID.fromString("0199c000-0000-7000-8000-000000000001");
    static final UUID OTHER = UUID.fromString("0199c000-0000-7000-8000-000000000002");
    static final UUID TENANT_BOOK = UUID.fromString("0199c000-0000-7000-8000-0000000000a1");
    static final UUID TENANT_SUSPENSE = UUID.fromString("0199c000-0000-7000-8000-0000000000a2");
    static final UUID DEPLOYED_BOOK = UUID.fromString("0199c000-0000-7000-8000-0000000000d1");
    static final UUID DEPLOYED_SUSPENSE = UUID.fromString("0199c000-0000-7000-8000-0000000000d2");
    static final UUID DEPLOYED_LOSS = UUID.fromString("0199c000-0000-7000-8000-0000000000d3");

    @AfterEach
    void clean() {
        TenantContext.clear();
        TenantSettings.uninstall();
    }

    /** config-service with ledger settings for {@link #TENANT} only; any other call fails the test. */
    static ServiceClient config() {
        return new ServiceClient(JSON, new MockEnvironment()) {
            @Override
            public JsonNode get(String service, String path) {
                assertThat(service).isEqualTo("config-service");
                String values = TENANT.equals(TenantContext.require())
                        ? "{\"card-dispute-service\":{\"ledger_id\":\"" + TENANT_BOOK
                          + "\",\"dispute\":{\"suspense_account\":\"" + TENANT_SUSPENSE + "\"}}}" : "{}";
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
        DisputeProperties props = new DisputeProperties(DEPLOYED_BOOK,
                new DisputeProperties.Dispute(DEPLOYED_SUSPENSE, DEPLOYED_LOSS, null));

        TenantContext.set(TENANT, "u");
        assertThat(props.ledgerId()).isEqualTo(TENANT_BOOK);
        assertThat(props.dispute().suspenseAccount()).isEqualTo(TENANT_SUSPENSE);
        assertThat(props.dispute().lossExpenseAccount()).isEqualTo(DEPLOYED_LOSS);
        assertThat(props.dispute().schemeReceivableAccount()).isNull();

        TenantContext.set(OTHER, "u");
        assertThat(props.ledgerId()).isEqualTo(DEPLOYED_BOOK);
        assertThat(props.dispute().suspenseAccount()).isEqualTo(DEPLOYED_SUSPENSE);
    }

    @Test
    void withoutTenantOrDeploymentLedgerNothingIsPosted() {
        ServiceClient client = config();
        new TenantSettings(client, 30_000).install();
        TenantContext.set(OTHER, "u");
        Downstream downstream = new Downstream(client, new DisputeProperties(null, null), JSON);

        assertThatThrownBy(() -> downstream.post("k", "provisional credit", "EUR", UUID.randomUUID(),
                List.of(new Downstream.Leg(UUID.randomUUID(), "DEBIT", BigDecimal.TEN, "x"))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(422);
                    assertThat(e.code()).isEqualTo("ledger_not_configured");
                });
        assertThatThrownBy(downstream::suspense)
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(422);
                    assertThat(e.code()).isEqualTo("ledger_account_not_configured");
                });
    }
}
