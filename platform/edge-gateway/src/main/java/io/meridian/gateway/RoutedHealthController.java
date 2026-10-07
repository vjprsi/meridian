package io.meridian.gateway;

import java.util.Map;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The actuator lives on the management port (SEC-31: metrics never behind the ingress), but the cell router probes
 * {@code <gateway_url>/actuator/health/readiness} on the routed port (cell health, S20). These two status-only
 * answers keep that contract: 200 {@code {"status":"UP"}} or 503 with the group's status, no details.
 */
@RestController
class RoutedHealthController {
    private final HealthEndpoint health;

    RoutedHealthController(HealthEndpoint health) {
        this.health = health;
    }

    @GetMapping({"/actuator/health/readiness", "/actuator/health/liveness", "/actuator/health"})
    ResponseEntity<Map<String, String>> health(jakarta.servlet.http.HttpServletRequest request) {
        String uri = request.getRequestURI();
        String group = uri.endsWith("/readiness") ? "readiness" : uri.endsWith("/liveness") ? "liveness" : null;
        var descriptor = group == null ? health.health() : health.healthForPath(group);
        Status status = descriptor == null ? Status.UNKNOWN : descriptor.getStatus();
        return ResponseEntity.status(Status.UP.equals(status) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", status.getCode()));
    }
}
