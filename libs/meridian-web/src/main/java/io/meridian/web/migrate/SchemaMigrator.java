package io.meridian.web.migrate;

import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.MigrateResult;
import org.springframework.core.env.Environment;

/**
 * Runs the service's Flyway migrations as the schema owner and nothing else (GAP-22).
 *
 * <p>In a cluster the long-lived Deployment runs with the RLS-bound runtime role only ({@code spring.flyway.enabled=false},
 * no owner credentials in the pod). Migrations run in a Helm {@code pre-install,pre-upgrade} Job from the same image
 * with {@code MERIDIAN_MIGRATE_ONLY=true} (or the program argument {@code migrate}): {@link MigrateRunListener} sees the
 * flag once the environment is prepared, calls {@link #migrate(Environment)} and exits before any bean (web server,
 * Kafka, schedulers) is created. Local development and tests keep in-process Flyway.
 *
 * <p>Properties honoured (same names and defaults as Spring Boot's Flyway auto-configuration):
 * {@code spring.flyway.url} (default {@code spring.datasource.url}), {@code .user} / {@code .password} (default the
 * datasource's), {@code .schemas}, {@code .default-schema}, {@code .locations} (default {@code classpath:db/migration}),
 * {@code .table}, {@code .create-schemas}, {@code .baseline-on-migrate}, {@code .out-of-order}.
 */
public final class SchemaMigrator {
    private SchemaMigrator() {}

    /** Outcome of a migrate-only run. */
    public record Result(String url, String user, List<String> schemas, int migrationsExecuted, String targetVersion) {}

    /** The Flyway configuration the run would use (no connection is opened). */
    public static FluentConfiguration configure(Environment env) {
        String url = first(env, "spring.flyway.url", "spring.datasource.url");
        if (url == null) {
            throw new IllegalStateException("Neither spring.flyway.url nor spring.datasource.url is set; nothing to migrate");
        }
        String user = first(env, "spring.flyway.user", "spring.datasource.username");
        String password = first(env, "spring.flyway.password", "spring.datasource.password");
        FluentConfiguration cfg = Flyway.configure()
                .dataSource(url, user, password)
                .locations(env.getProperty("spring.flyway.locations", String[].class, new String[] {"classpath:db/migration"}))
                .createSchemas(env.getProperty("spring.flyway.create-schemas", Boolean.class, true))
                .baselineOnMigrate(env.getProperty("spring.flyway.baseline-on-migrate", Boolean.class, false))
                .outOfOrder(env.getProperty("spring.flyway.out-of-order", Boolean.class, false))
                .table(env.getProperty("spring.flyway.table", "flyway_schema_history"));
        String[] schemas = env.getProperty("spring.flyway.schemas", String[].class, new String[0]);
        if (schemas.length > 0) {
            cfg.schemas(schemas);
        }
        String defaultSchema = env.getProperty("spring.flyway.default-schema");
        if (defaultSchema != null && !defaultSchema.isBlank()) {
            cfg.defaultSchema(defaultSchema);
        }
        return cfg;
    }

    /** Applies pending migrations; fails (exception) on any validation or migration error. */
    public static Result migrate(Environment env) {
        FluentConfiguration cfg = configure(env);
        MigrateResult r = cfg.load().migrate();
        if (!r.success) {
            throw new IllegalStateException("Flyway migration failed: " + r.warnings);
        }
        return new Result(cfg.getUrl(), cfg.getUser(), List.of(cfg.getSchemas()), r.migrationsExecuted, r.targetSchemaVersion);
    }

    private static String first(Environment env, String... names) {
        for (String n : names) {
            String v = env.getProperty(n);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
