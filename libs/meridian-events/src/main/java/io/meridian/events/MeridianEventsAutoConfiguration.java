package io.meridian.events;

import io.meridian.common.cell.CellRole;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Outbox writer for every service; the Kafka relay unless {@code meridian.events.relay-enabled=false}.
 *
 * <p>MR-GAME-DAY: the cell role ({@code meridian.cell.role}, Helm {@code MERIDIAN_CELL_ROLE}) is a runtime switch
 * ({@link CellRole}); a standby cell starts with the relay paused and the listeners held ({@link StandbyGate}) and is
 * promoted in place. Relayed rows are kept for {@code meridian.events.retention.window} (default 72h) for
 * {@link OutboxReplay}, then purged by {@link OutboxRetention}.
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration"})
@EnableScheduling
public class MeridianEventsAutoConfiguration {

    /** The cell's runtime role (shared with meridian-web, which exposes the operator switch). */
    @Bean
    @ConditionalOnMissingBean
    CellRole meridianCellRole(Environment env) {
        return new CellRole(env.getProperty("meridian.cell.id", ""), CellRole.parse(env.getProperty("meridian.cell.role")));
    }

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
                            @Value("${meridian.events.retention.window:PT72H}") Duration retention,
                            CellRole role,
                            ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meters) {
        OutboxRelay relay = new OutboxRelay(jdbc, kafka, tx, topic, source, batchSize, retention, !role.active());
        meters.ifAvailable(relay::bindMetrics);
        return relay;
    }

    @Bean
    @ConditionalOnProperty(name = "meridian.events.retention.purge-enabled", havingValue = "true", matchIfMissing = true)
    OutboxRetention outboxRetention(JdbcClient jdbc, CellRole role,
                                    @Value("${meridian.events.retention.window:PT72H}") Duration retention,
                                    @Value("${meridian.events.retention.purge-batch-size:5000}") int batch,
                                    ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meters) {
        OutboxRetention r = new OutboxRetention(jdbc, retention, batch, role);
        meters.ifAvailable(r::bindMetrics);
        return r;
    }

    @Bean
    @ConditionalOnBean(KafkaTemplate.class)
    OutboxReplay outboxReplay(JdbcClient jdbc, KafkaTemplate<String, String> kafka,
                              @Value("${meridian.events.topic}") String topic,
                              @Value("${spring.application.name}") String source,
                              @Value("${meridian.events.replay.max-per-second:2000}") int maxPerSecond,
                              ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meters) {
        OutboxReplay r = new OutboxReplay(jdbc, kafka, topic, source, maxPerSecond);
        meters.ifAvailable(r::bindMetrics);
        return r;
    }

    @Bean
    StandbyGate standbyGate(CellRole role, ObjectProvider<KafkaListenerEndpointRegistry> registry,
                            ObjectProvider<OutboxRelay> relay, ObjectProvider<OutboxReplay> replay, JdbcClient jdbc,
                            @Value("${meridian.events.retention.replay-on-promote:PT0S}") Duration replayOnPromote,
                            @Value("${meridian.cell.promotion.db-wait:PT10M}") Duration dbWait,
                            @Value("${meridian.cell.promotion.db-poll:PT1S}") Duration dbPoll) {
        return new StandbyGate(role, registry, relay.getIfAvailable(), replay.getIfAvailable(), replayOnPromote,
                StandbyGate.postgresWritable(jdbc), dbWait, dbPoll);
    }
}
