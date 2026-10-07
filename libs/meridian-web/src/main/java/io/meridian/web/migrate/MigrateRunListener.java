package io.meridian.web.migrate;

import java.util.Arrays;
import java.util.function.IntConsumer;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringApplicationRunListener;
import org.springframework.boot.bootstrap.ConfigurableBootstrapContext;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

/**
 * Migrate-only entry point (GAP-22): when the service is started with {@code MERIDIAN_MIGRATE_ONLY=true} or the
 * argument {@code migrate}, runs Flyway as the schema owner ({@link SchemaMigrator}) right after the environment
 * (application.yml + env) is prepared and logging is configured, then exits the JVM: 0 on success, 1 on failure. No
 * application context, web server, Kafka client or scheduler is ever created, so the Helm migration Job needs only
 * the database URL and the owner credentials. Registered in {@code META-INF/spring.factories}; a normal start is
 * untouched (Flyway classes are only loaded for a migrate-only run).
 */
public class MigrateRunListener implements SpringApplicationRunListener, Ordered {
    /** Property (env {@code MERIDIAN_MIGRATE_ONLY}) that turns a service start into a migrate-and-exit run. */
    public static final String MIGRATE_ONLY = "meridian.migrate-only";
    /** Program argument with the same effect. */
    public static final String MIGRATE_ARG = "migrate";

    private static final Log log = LogFactory.getLog(MigrateRunListener.class);
    /** Test seam: how the run ends (the JVM exits in production). */
    static volatile IntConsumer exit = System::exit;

    private final String[] args;

    public MigrateRunListener(SpringApplication application, String[] args) {
        this.args = args;
    }

    /** Test seam: replaces how a migrate-only run ends (default {@code System::exit}); returns the previous handler. */
    public static IntConsumer onExit(IntConsumer handler) {
        IntConsumer previous = exit;
        exit = handler;
        return previous;
    }

    /** True when this start is a migrate-only run (argument {@code migrate} / {@code --migrate} or the property). */
    public static boolean requested(String[] args, Environment env) {
        if (args != null && Arrays.stream(args).anyMatch(a -> MIGRATE_ARG.equals(a) || ("--" + MIGRATE_ARG).equals(a))) {
            return true;
        }
        return env != null && env.getProperty(MIGRATE_ONLY, Boolean.class, false);
    }

    @Override
    public void environmentPrepared(ConfigurableBootstrapContext bootstrapContext, ConfigurableEnvironment environment) {
        if (!requested(args, environment)) {
            return;
        }
        int code;
        try {
            SchemaMigrator.Result r = SchemaMigrator.migrate(environment);
            log.info("Migrate-only run finished: " + r.migrationsExecuted() + " migration(s) applied to " + r.schemas()
                    + " as " + r.user() + ", schema version " + r.targetVersion());
            code = 0;
        } catch (RuntimeException e) {
            log.error("Migrate-only run failed", e);
            code = 1;
        }
        exit.accept(code);
    }

    /** After Spring Boot's own listener (order 0), which configures logging. */
    @Override
    public int getOrder() {
        return 10;
    }
}
