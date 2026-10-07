package io.meridian.rewards.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.client.ServiceClient;
import io.meridian.web.config.TenantSettings;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Payout ledger book / funding account: the program's own values, else the tenant's config-service settings, else 422. */
class PayoutLedgerSettingsTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final UUID TENANT = UUID.fromString("0199c000-0000-7000-8000-000000000001");
    static final UUID OTHER = UUID.fromString("0199c000-0000-7000-8000-000000000002");
    static final UUID TENANT_BOOK = UUID.fromString("0199c000-0000-7000-8000-0000000000a1");
    static final UUID TENANT_FUNDING = UUID.fromString("0199c000-0000-7000-8000-0000000000a2");
    static final UUID PROGRAM_BOOK = UUID.fromString("0199c000-0000-7000-8000-0000000000b1");
    static final UUID CUSTOMER = UUID.fromString("0199c000-0000-7000-8000-0000000000c1");

    final List<JsonNode> postings = new ArrayList<>();

    final ServiceClient client = new ServiceClient(JSON, new MockEnvironment()) {
        @Override
        public JsonNode get(String service, String path) {
            assertThat(service).isEqualTo("config-service");
            String values = TENANT.equals(TenantContext.require())
                    ? "{\"rewards-service\":{\"ledger_id\":\"" + TENANT_BOOK + "\",\"funding_account_id\":\""
                      + TENANT_FUNDING + "\"}}" : "{}";
            return JSON.readTree("{\"namespace\":\"ledger\",\"values\":" + values + "}");
        }

        @Override
        public JsonNode post(String service, String path, Object body, String key) {
            assertThat(service).isEqualTo("ledger-service");
            postings.add(JSON.valueToTree(body));
            return JSON.readTree("{\"id\":\"" + UUID.randomUUID() + "\"}");
        }
    };

    @AfterEach
    void clean() {
        TenantContext.clear();
        TenantSettings.uninstall();
    }

    @Test
    void tenantSettingsFillWhatTheProgramLeavesUnset() {
        new TenantSettings(client, 30_000).install();
        ExternalServices external = new ExternalServices(client, JSON);

        TenantContext.set(TENANT, "u");
        external.payout(null, null, CUSTOMER, new BigDecimal("5"), "GBP", "CASHBACK", UUID.randomUUID(), "cb", "k1");
        external.payout(PROGRAM_BOOK, null, CUSTOMER, new BigDecimal("5"), "GBP", "CASHBACK", UUID.randomUUID(), "cb", "k2");
        assertThat(postings.get(0).get("ledger_id").asString()).isEqualTo(TENANT_BOOK.toString());
        assertThat(postings.get(0).get("entries").get(0).get("ledger_account_id").asString()).isEqualTo(TENANT_FUNDING.toString());
        assertThat(postings.get(1).get("ledger_id").asString()).isEqualTo(PROGRAM_BOOK.toString());

        TenantContext.set(OTHER, "u");
        assertThatThrownBy(() -> external.payout(null, TENANT_FUNDING, CUSTOMER, BigDecimal.ONE, "GBP", "CASHBACK",
                UUID.randomUUID(), "cb", "k3"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(422);
                    assertThat(e.code()).isEqualTo("ledger_not_configured");
                });
        assertThat(postings).hasSize(2);
    }
}
