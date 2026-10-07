package io.meridian.router;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import tools.jackson.databind.json.JsonMapper;

/** Wiring of the router's components. */
@Configuration(proxyBeanMethods = false)
public class RouterConfiguration {

    @Bean
    Clock routerClock() {
        return Clock.systemUTC();
    }

    @Bean
    HeaderPolicy headerPolicy(RouterProperties props, @Value("${meridian.security-mode:jwt}") String securityMode) {
        return new HeaderPolicy(securityMode, props.trustedProxies());
    }

    @Bean
    CellDirectory cellDirectory(RouterProperties props) {
        return new CellDirectory(props);
    }

    @Bean
    PlacementCache placementCache(RegistryClient registry, CellDirectory cells, RouterProperties props,
                                  MeterRegistry meters, Clock routerClock) {
        return new PlacementCache(registry, cells, props, meters, routerClock);
    }

    @Bean
    PublicRoutes publicRoutes(JsonMapper json) throws IOException {
        return new PublicRoutes(json);
    }

    @Bean
    TenantResolver tenantResolver(RouterProperties props, HeaderPolicy headers) {
        // the decoder is built on first use, so the router starts (and serves health) before the IdP is reachable
        java.util.concurrent.atomic.AtomicReference<JwtDecoder> decoder = new java.util.concurrent.atomic.AtomicReference<>();
        return new TenantResolver(headers.devMode(), props.jwt().tenantClaim(), () -> decoder.updateAndGet(
                d -> d != null ? d : TenantResolver.decoder(props.jwt())));
    }

    @Bean
    RegistryEvents registryEvents(PlacementCache placements, CellDirectory cells, JsonMapper json) {
        return new RegistryEvents(placements, cells, json);
    }
}
