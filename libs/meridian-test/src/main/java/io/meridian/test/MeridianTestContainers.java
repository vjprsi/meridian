package io.meridian.test;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL 18 and Kafka for integration tests ({@code @Import(MeridianTestContainers.class)}).
 * Flyway migrates as the owner ({@code meridian}); the service connects as the non-superuser
 * {@code meridian_app}, exactly as in production, so row-level security is really enforced in tests.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MeridianTestContainers {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18"))
            .withDatabaseName("meridian_test")
            .withUsername("meridian")
            .withPassword("meridian")
            .withInitScript("meridian-test-init.sql");

    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.0"));

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @Bean
    DynamicPropertyRegistrar meridianTestProperties(Environment env) {
        String schema = env.getProperty("spring.flyway.default-schema", "public");
        return registry -> {
            String url = POSTGRES.getJdbcUrl() + "&currentSchema=" + schema;
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> "meridian_app");
            registry.add("spring.datasource.password", () -> "meridian_app");
            registry.add("spring.flyway.url", () -> url);
            registry.add("spring.flyway.user", () -> "meridian");
            registry.add("spring.flyway.password", () -> "meridian");
            registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        };
    }
}
