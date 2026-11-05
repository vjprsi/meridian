package io.meridian.biller.infrastructure.connector;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * One {@link CircuitBreaker} per provider key (aggregator or biller code), created on first use and shared across
 * connectors and calls. The threshold / cooldown are fixed when the breaker is first created (stable per provider);
 * later configuration changes take effect on restart.
 */
@Component
public class CircuitBreakerRegistry {
    private final ConcurrentHashMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    public CircuitBreaker breaker(String key, int threshold, long cooldownMs) {
        return breakers.computeIfAbsent(key, k -> new CircuitBreaker(threshold, cooldownMs));
    }
}
