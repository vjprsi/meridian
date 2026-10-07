package io.meridian.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.test.MeridianTestContainers;
import io.meridian.web.migrate.MigrateRunListener;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * GAP-22: the Helm migration Job starts the service image with {@code migrate}; Flyway runs as the schema owner and the
 * process ends before the application context (web server, Kafka, schedulers) exists.
 */
class MigrateOnlyIT {
    private IntConsumer previous;

    @AfterEach
    void restore() {
        if (previous != null) {
            MigrateRunListener.onExit(previous);
        }
    }

    @Test
    void migrateOnlyRunAppliesTheSchemaAndStopsBeforeTheContext() throws Exception {
        String admin = MeridianTestContainers.adminJdbcUrl();
        try (Connection c = DriverManager.getConnection(admin, "meridian", "meridian")) {
            c.createStatement().execute("drop database if exists customer_migrate_it");
            c.createStatement().execute("create database customer_migrate_it");
        }
        String url = admin.replaceFirst("/[^/?]+(\\?|$)", "/customer_migrate_it$1");
        int[] code = {-1};
        previous = MigrateRunListener.onExit(c -> {
            code[0] = c;
            throw new MigrationFinished();
        });
        for (int run = 1; run <= 2; run++) {
            // what the Job passes (environment variables there; command-line arguments here, same precedence over
            // application.yml); no Kafka and no runtime credentials: the Job never reaches the context
            assertThatThrownBy(() -> new SpringApplicationBuilder(CustomerApplication.class)
                    .run("migrate", "--spring.datasource.url=" + url, "--spring.flyway.user=meridian",
                            "--spring.flyway.password=meridian", "--spring.kafka.bootstrap-servers=unreachable:1"))
                    .isInstanceOf(MigrationFinished.class);
            assertThat(code[0]).as("exit code of run %d", run).isZero();
        }
        try (Connection c = DriverManager.getConnection(url, "meridian", "meridian");
             ResultSet rs = c.createStatement().executeQuery(
                     "select count(*) from customer.flyway_schema_history where success")) {
            rs.next();
            assertThat(rs.getInt(1)).isPositive();
        }
        try (Connection c = DriverManager.getConnection(url, "meridian", "meridian");
             ResultSet rs = c.createStatement().executeQuery("select to_regclass('customer.customer_contact') is not null")) {
            rs.next();
            assertThat(rs.getBoolean(1)).isTrue();
        }
    }

    static final class MigrationFinished extends RuntimeException {
        MigrationFinished() {
            super("migrate-only run finished", null, false, false);
        }
    }
}
