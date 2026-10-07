package io.meridian.events;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.common.cell.CellRole;
import java.time.Duration;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * MR-GAME-DAY: in a real Spring context the gate runs before the listener registry, so a standby service never starts
 * a listener container - not even one with its own {@code autoStartup="true"} expression - until promotion.
 */
class StandbyGateContextTest {

    @Configuration(proxyBeanMethods = false)
    @EnableKafka
    static class App {
        @Bean
        ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
            var f = new ConcurrentKafkaListenerContainerFactory<String, String>();
            f.setConsumerFactory(new DefaultKafkaConsumerFactory<>(Map.of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:1",
                    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)));
            return f;
        }

        @Bean
        Listener listener() {
            return new Listener();
        }

        @Bean
        CellRole cellRole() {
            return new CellRole("euw2c01", CellRole.Role.STANDBY);
        }

        @Bean
        StandbyGate standbyGate(CellRole role, ObjectProvider<KafkaListenerEndpointRegistry> registry) {
            return new StandbyGate(role, registry, null, null, Duration.ZERO, () -> true, Duration.ofSeconds(5),
                    Duration.ofMillis(10));
        }
    }

    static class Listener {
        @KafkaListener(id = "own-autostartup", topics = "t", groupId = "g", autoStartup = "true")
        void onMessage(String m) {
        }

        @KafkaListener(id = "default-autostartup", topics = "t", groupId = "g2")
        void onOther(String m) {
        }
    }

    @Test
    void listenersStayStoppedInAStandbyCellUntilPromoted() throws Exception {
        try (var ctx = new AnnotationConfigApplicationContext(App.class)) {
            KafkaListenerEndpointRegistry registry = ctx.getBean(KafkaListenerEndpointRegistry.class);
            assertThat(registry.getListenerContainers()).hasSize(2)
                    .noneMatch(MessageListenerContainer::isRunning);
            StandbyGate gate = ctx.getBean(StandbyGate.class);
            assertThat(gate.heldListeners()).containsExactlyInAnyOrder("own-autostartup", "default-autostartup");

            ctx.getBean(CellRole.class).promote("game day", "platform-ops");
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (gate.state() != StandbyGate.State.ACTIVE && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(gate.state()).isEqualTo(StandbyGate.State.ACTIVE);
            assertThat(registry.getListenerContainers()).allMatch(MessageListenerContainer::isRunning);
        }
    }
}
