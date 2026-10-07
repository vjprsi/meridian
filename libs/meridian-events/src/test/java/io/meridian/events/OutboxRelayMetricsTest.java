package io.meridian.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** GAP-10: the outbox backlog gauges read the outbox at scrape time and never fail the scrape. */
class OutboxRelayMetricsTest {

    @Test
    @SuppressWarnings("unchecked")
    void backlogGaugesReportRowsAndOldestAgeAndNaNWhenTheDatabaseFails() {
        JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        when(jdbc.sql(anyString()).query(any(RowMapper.class)).single()).thenReturn(new double[] {3, 42.5});
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        new OutboxRelay(jdbc, null, null, "meridian.test.v1", "test-service", 10).bindMetrics(meters);

        assertThat(meters.get("meridian.outbox.backlog").gauge().value()).isEqualTo(3.0);
        assertThat(meters.get("meridian.outbox.oldest.age").gauge().value()).isEqualTo(42.5);
        assertThat(meters.get("meridian.outbox.relayed").counter().count()).isZero();
        assertThat(meters.get("meridian.outbox.relay.failures").counter().count()).isZero();

        when(jdbc.sql(anyString()).query(any(RowMapper.class)).single()).thenThrow(new IllegalStateException("down"));
        assertThat(meters.get("meridian.outbox.backlog").gauge().value()).isNaN();
    }
}
