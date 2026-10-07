package io.meridian.web.logging;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * GAP-06: application logs are one JSON object per line on stdout (Spring Boot structured logging, Elastic Common
 * Schema), ready for a node-level collector (OpenTelemetry Collector filelog receiver or Promtail) to ship to Loki /
 * the SIEM. Every line carries the service name, the cell (when {@code MERIDIAN_CELL_ID} is set), the trace and span
 * ids (Micrometer tracing) and the request MDC: {@code request_id} ({@code RequestIdFilter}), {@code tenant_id},
 * {@code principal} and {@code calling_service} ({@code TenantFilter}); principals are opaque ids, never names, e-mail
 * addresses or payloads.
 *
 * <p>{@code meridian.logging.format}: {@code json} (default) or {@code text}; it defaults to {@code text} in dev
 * security mode (local runs and tests stay readable). An explicit {@code logging.structured.format.console} wins.
 * Registered in {@code META-INF/spring.factories}; runs after the config files are loaded and before the logging
 * system starts.
 */
public class StructuredLoggingDefaults implements EnvironmentPostProcessor, Ordered {
    static final String SOURCE = "meridianStructuredLogging";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        if (env.containsProperty("logging.structured.format.console")) {
            return;
        }
        boolean dev = "dev".equalsIgnoreCase(env.getProperty("meridian.security-mode", "jwt"));
        String format = env.getProperty("meridian.logging.format", dev ? "text" : "json");
        if (!"json".equalsIgnoreCase(format.trim())) {
            return;
        }
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("logging.structured.format.console", "ecs");
        String app = env.getProperty("spring.application.name");
        if (app != null && !app.isBlank()) {
            defaults.put("logging.structured.ecs.service.name", app);
        }
        String cell = env.getProperty("meridian.cell.id", env.getProperty("MERIDIAN_CELL_ID"));
        if (cell != null && !cell.isBlank()) {
            defaults.put("logging.structured.ecs.service.node-name", cell);
        }
        env.getPropertySources().addLast(new MapPropertySource(SOURCE, defaults));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
