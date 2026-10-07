package io.meridian.deposit;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.test.DataLifecycleCheck;
import io.meridian.test.MeridianTestContainers;
import io.meridian.test.TestRows;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * GAP-08: cheques of a customer's account (linked through the account) of a customer are exported, erased and reachable for archiving through the internal
 * data-lifecycle API (real PostgreSQL, row-level security on).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "meridian.events.relay-enabled=false")
@Import(MeridianTestContainers.class)
class DataLifecycleIT {
    @Value("${local.server.port}")
    int port;

    @Test
    void personalDataIsExportedErasedAndArchivable() {
        DataLifecycleCheck check = new DataLifecycleCheck(port, "accounts");
        UUID id;
        try (TestRows rows = check.rows()) {
            UUID account = rows.insert("deposit_account", Map.of("customer_id", check.customer()));
            id = rows.insert("cheque", Map.of("deposit_account_id", account, "payee_name", "Jane Payee"));
        }
        assertThat(check.verify("cheque", id, "payee_name", "Jane Payee")).isNull();
    }
}
