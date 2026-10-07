package io.meridian.kyc;

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
 * GAP-08: KYC documents of a customer are exported, erased and reachable for archiving through the internal
 * data-lifecycle API (real PostgreSQL, row-level security on).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "meridian.events.relay-enabled=false")
@Import(MeridianTestContainers.class)
class DataLifecycleIT {
    @Value("${local.server.port}")
    int port;

    @Test
    void personalDataIsExportedErasedAndArchivable() {
        DataLifecycleCheck check = new DataLifecycleCheck(port, "kyc");
        UUID id;
        try (TestRows rows = check.rows()) {
            id = rows.insert("kyc_document", Map.of("customer_id", check.customer(), "document_number", "P1234567"));
        }
        assertThat(check.verify("kyc_document", id, "document_number", "P1234567")).isNull();
    }
}
