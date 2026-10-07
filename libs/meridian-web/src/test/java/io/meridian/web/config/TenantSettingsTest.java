package io.meridian.web.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.client.ServiceClient;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class TenantSettingsTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final UUID T1 = UUID.fromString("0199a000-0000-7000-8000-000000000001");
    static final UUID T2 = UUID.fromString("0199a000-0000-7000-8000-000000000002");
    static final UUID BOOK = UUID.fromString("0199a000-0000-7000-8000-0000000000b1");
    static final UUID CLEARING = UUID.fromString("0199a000-0000-7000-8000-0000000000c1");
    static final UUID DEPLOYED = UUID.fromString("0199a000-0000-7000-8000-0000000000d1");

    /** config-service stub: per-tenant resolve responses, counting calls. */
    static final class FakeConfig extends ServiceClient {
        final Map<UUID, String> byTenant = new HashMap<>();
        final AtomicInteger calls = new AtomicInteger();
        boolean down;

        FakeConfig() {
            super(JSON, new MockEnvironment());
        }

        @Override
        public JsonNode get(String service, String path) {
            calls.incrementAndGet();
            assertThat(service).isEqualTo("config-service");
            assertThat(path).isEqualTo("/v1/config/entries/resolve?namespace=ledger");
            if (down) {
                throw new ApiException(502, "upstream_error", "config-service is not reachable");
            }
            return JSON.readTree(byTenant.getOrDefault(TenantContext.require(), "{\"namespace\":\"ledger\",\"values\":{}}"));
        }
    }

    @AfterEach
    void clean() {
        TenantContext.clear();
        TenantSettings.uninstall();
    }

    @Test
    void tenantValuesWinOverDeploymentDefaultsAndAreIsolatedPerTenant() {
        FakeConfig config = new FakeConfig();
        config.byTenant.put(T1, """
                {"values":{"default":{"ledger_id":"%s"},"deposit-service":{"gl":{"clearing":"%s","fee_income":""}}}}"""
                .formatted(BOOK, CLEARING));
        new TenantSettings(config, 30_000).install();

        TenantContext.set(T1, "u");
        assertThat(TenantSettings.uuid("deposit-service", "ledger_id", DEPLOYED)).isEqualTo(BOOK);   // from "default"
        assertThat(TenantSettings.uuid("deposit-service", "gl.clearing", DEPLOYED)).isEqualTo(CLEARING);
        assertThat(TenantSettings.uuid("deposit-service", "gl.fee_income", DEPLOYED)).isEqualTo(DEPLOYED); // blank = unset
        assertThat(TenantSettings.uuid("deposit-service", "gl.tax_payable", null)).isNull();

        TenantContext.set(T2, "u");
        assertThat(TenantSettings.uuid("deposit-service", "ledger_id", DEPLOYED)).isEqualTo(DEPLOYED);
        assertThat(TenantSettings.uuid("deposit-service", "gl.clearing", null)).isNull();
    }

    @Test
    void cachesPerTenantForTheTtlAndFallsBackWhenUnavailableOrUnbound() {
        FakeConfig config = new FakeConfig();
        config.byTenant.put(T1, "{\"values\":{\"payment-service\":{\"ledger_id\":\"" + BOOK + "\"}}}");
        AtomicLong now = new AtomicLong(1_000);
        new TenantSettings(config, 30_000, now::get).install();

        assertThat(TenantSettings.uuid("payment-service", "ledger_id", DEPLOYED)).isEqualTo(DEPLOYED); // no tenant bound
        assertThat(config.calls.get()).isZero();

        TenantContext.set(T1, "u");
        assertThat(TenantSettings.uuid("payment-service", "ledger_id", null)).isEqualTo(BOOK);
        assertThat(TenantSettings.uuid("payment-service", "ledger_id", null)).isEqualTo(BOOK);
        assertThat(config.calls.get()).isEqualTo(1);

        now.addAndGet(30_001);
        config.down = true;
        assertThat(TenantSettings.uuid("payment-service", "ledger_id", DEPLOYED)).isEqualTo(DEPLOYED);
        assertThat(config.calls.get()).isEqualTo(2);
    }

    @Test
    void notInstalledMeansDeploymentValue() {
        TenantContext.set(T1, "u");
        assertThat(TenantSettings.uuid("loan-service", "ledger_id", DEPLOYED)).isEqualTo(DEPLOYED);
    }
}
