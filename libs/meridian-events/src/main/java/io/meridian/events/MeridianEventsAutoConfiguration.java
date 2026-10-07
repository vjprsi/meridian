package io.meridian.events;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Outbox writer for every service; the Kafka relay unless {@code meridian.events.relay-enabled=false}. */
@AutoConfiguration(afterName = {
        "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration"})
@EnableScheduling
public class MeridianEventsAutoConfiguration {

    @Bean
    OutboxWriter outboxWriter(JdbcClient jdbc, JsonMapper json) {
        return new OutboxWriter(jdbc, json);
    }

    @Bean
    @ConditionalOnBean(KafkaTemplate.class)
    @ConditionalOnProperty(name = "meridian.events.relay-enabled", havingValue = "true", matchIfMissing = true)
    OutboxRelay outboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                            @Value("${meridian.events.topic}") String topic,
                            @Value("${spring.application.name}") String source,
                            @Value("${meridian.events.batch-size:200}") int batchSize,
                            org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meters) {
        OutboxRelay relay = new OutboxRelay(jdbc, kafka, tx, topic, source, batchSize);
        meters.ifAvailable(relay::bindMetrics);
        return relay;
    }
}
