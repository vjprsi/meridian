package io.meridian.inbox;

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
 * GAP-08: secure messages (append-only: reported as retained) of a customer are exported, erased and reachable for archiving through the internal
 * data-lifecycle API (real PostgreSQL, row-level security on).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "meridian.events.relay-enabled=false")
@Import(MeridianTestContainers.class)
class DataLifecycleIT {
    @Value("${local.server.port}")
    int port;

    @Test
    void personalDataIsExportedErasedAndArchivable() {
        DataLifecycleCheck check = new DataLifecycleCheck(port, "inbox");
        UUID id;
        try (TestRows rows = check.rows()) {
            UUID thread = rows.insert("secure_message_thread", Map.of("customer_id", check.customer()));
            id = rows.insert("secure_message", Map.of("secure_message_thread_id", thread, "body", "My new address is 1 High St"));
        }
        assertThat(check.verify("secure_message", id, "body", "My new address is 1 High St", "RETAINED")).isNull();
    }
}
