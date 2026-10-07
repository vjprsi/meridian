package io.meridian.web.migrate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class MigrateRunListenerTest {

    @AfterEach
    void restoreExit() {
        MigrateRunListener.exit = System::exit;
    }

    @Test
    void migrateOnlyIsRequestedByArgumentOrProperty() {
        assertThat(MigrateRunListener.requested(new String[] {"migrate"}, env(Map.of()))).isTrue();
        assertThat(MigrateRunListener.requested(new String[] {"--migrate"}, env(Map.of()))).isTrue();
        assertThat(MigrateRunListener.requested(new String[0], env(Map.of("meridian.migrate-only", "true")))).isTrue();
        assertThat(MigrateRunListener.requested(new String[0], env(Map.of()))).isFalse();
        assertThat(MigrateRunListener.requested(new String[] {"--server.port=1"}, env(Map.of("meridian.migrate-only", "false")))).isFalse();
    }

    @Test
    void flywayUsesOwnerCredentialsAndServiceSchemaFallingBackToTheDatasourceUrl() {
        var cfg = SchemaMigrator.configure(env(Map.of(
                "spring.datasource.url", "jdbc:postgresql://db:5432/customer_db?currentSchema=customer",
                "spring.datasource.username", "meridian_app",
                "spring.flyway.user", "meridian_owner",
                "spring.flyway.password", "owner-pw",
                "spring.flyway.schemas", "customer",
                "spring.flyway.default-schema", "customer")));
        assertThat(cfg.getUrl()).isEqualTo("jdbc:postgresql://db:5432/customer_db?currentSchema=customer");
        assertThat(cfg.getUser()).isEqualTo("meridian_owner");
        assertThat(cfg.getSchemas()).containsExactly("customer");
        assertThat(cfg.getDefaultSchema()).isEqualTo("customer");
        assertThat(cfg.getLocations()).extracting(Object::toString).containsExactly("classpath:db/migration");
    }

    @Test
    void normalStartIsUntouchedAndAFailedMigrationExitsNonZero() {
        AtomicInteger code = new AtomicInteger(-1);
        MigrateRunListener.exit = code::set;
        new MigrateRunListener(null, new String[0]).environmentPrepared(null, env(Map.of()));
        assertThat(code.get()).isEqualTo(-1);
        // migrate-only without any database URL: the run fails and exits 1 (the Job is marked failed)
        new MigrateRunListener(null, new String[] {"migrate"}).environmentPrepared(null, env(Map.of()));
        assertThat(code.get()).isEqualTo(1);
    }

    private static StandardEnvironment env(Map<String, Object> props) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        return env;
    }
}
