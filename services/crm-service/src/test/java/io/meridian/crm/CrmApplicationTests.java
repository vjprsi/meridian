package io.meridian.crm;

import io.meridian.test.MeridianTestContainers;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Boots the service against real PostgreSQL 18 and Kafka; Flyway applies the generated schema. */
@SpringBootTest(properties = "meridian.events.relay-enabled=false")
@Import(MeridianTestContainers.class)
class CrmApplicationTests {

    @Test
    void contextLoadsAndSchemaMigrates() {
    }
}
