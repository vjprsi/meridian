package io.meridian.web.metrics;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * GAP-10: metric defaults for every Meridian service, added as the lowest-precedence property source (application.yml,
 * environment variables and test properties override each entry).
 * <ul>
 *   <li>{@code http.server.requests} publishes a percentile histogram plus SLO buckets matching the latency SLOs of
 *       docs/operations/SLO_SLA.md (card auth 250 ms, ledger 250 ms, payment initiation 1 s), so p99 and
 *       "requests under threshold" can be computed in Prometheus.</li>
 *   <li>Business timers ({@code meridian.*}) publish histograms with SLO buckets per journey.</li>
 *   <li>OTLP export uses seconds, so OTLP-translated names match the Prometheus registry
 *       ({@code http_server_requests_seconds_*}) and one set of dashboards / alert rules serves both pipelines.</li>
 *   <li>Every meter carries {@code cell} (S20) so SLOs can be computed per cell.</li>
 * </ul>
 */
public class MetricsDefaults implements EnvironmentPostProcessor {
    public static final String SOURCE = "meridianMetricsDefaults";
    private static final String D = "management.metrics.distribution.";

    static Map<String, Object> defaults() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(D + "percentiles-histogram.http.server.requests", "true");
        m.put(D + "slo.http.server.requests", "25ms,50ms,100ms,250ms,500ms,1s,2s,5s");
        m.put(D + "minimum-expected-value.http.server.requests", "1ms");
        m.put(D + "maximum-expected-value.http.server.requests", "30s");
        m.put(D + "percentiles-histogram.http.client.requests", "true");
        m.put(D + "percentiles-histogram.hikaricp.connections", "true");
        m.put(D + "percentiles-histogram.meridian", "true");
        m.put(D + "slo.meridian.payments.initiation.duration", "100ms,250ms,500ms,1s,2s,5s");
        m.put(D + "slo.meridian.ledger.posting.duration", "10ms,25ms,50ms,100ms,250ms,500ms,1s");
        m.put(D + "slo.meridian.cards.authorization.duration", "25ms,50ms,100ms,250ms,500ms,1s");
        m.put(D + "slo.meridian.eod.sweep.duration", "1m,5m,15m,30m,1h,2h,4h,8h");
        m.put(D + "maximum-expected-value.meridian.eod", "12h");
        m.put("management.metrics.tags.cell", "${meridian.cell.id:local}");
        m.put("management.otlp.metrics.export.base-time-unit", "seconds");
        return m;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        if (!env.getPropertySources().contains(SOURCE)) {
            env.getPropertySources().addLast(new MapPropertySource(SOURCE, defaults()));
        }
    }
}
