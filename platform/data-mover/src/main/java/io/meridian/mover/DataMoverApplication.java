package io.meridian.mover;

import java.net.InetAddress;
import java.util.UUID;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Data mover for live tenant moves between cells (MR-GAME-DAY). See {@link TenantMover}. */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(MoverProperties.class)
public class DataMoverApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataMoverApplication.class, args);
    }

    /** Exact numbers: rows pass through unchanged (decimals never become doubles). */
    @Bean
    MoverHttp moverHttp(MoverProperties props) {
        JsonMapper json = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).build();
        return new MoverHttp(json, props.auth());
    }

    @Bean
    ProgressStore progressStore(JdbcClient jdbc, MoverHttp http) {
        return new ProgressStore(jdbc, http.json());
    }

    @Bean
    Cells cells(MoverProperties props, MoverHttp http) {
        return new Cells(props, http.json());
    }

    @Bean
    TenantMover tenantMover(ProgressStore store, Cells cells, MoverHttp http, MoverProperties props,
                            org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meters)
            throws Exception {
        TenantMover mover = new TenantMover(store, cells, http, props,
                InetAddress.getLocalHost().getHostName() + "-" + UUID.randomUUID().toString().substring(0, 8));
        meters.ifAvailable(mover::bindMetrics);
        return mover;
    }

    @Bean
    MoveScheduler moveScheduler(TenantMover mover) {
        return new MoveScheduler(mover);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "meridian.mover.events.enabled",
            havingValue = "true", matchIfMissing = true)
    CopyRequestListener copyRequestListener(TenantMover mover, MoverHttp http, MoveScheduler scheduler) {
        return new CopyRequestListener(mover, http.json(), scheduler);
    }

    @Bean
    MovesEndpoint movesEndpoint(ProgressStore store, MoveScheduler scheduler) {
        return new MovesEndpoint(store, scheduler);
    }
}
