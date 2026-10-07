package io.meridian.router;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * The Helm chart passes {@code meridian.router.*} as SPRING_APPLICATION_JSON with the camelCase keys of its values
 * file (flattened like below): they must bind, including cell override keys that contain dashes.
 */
class RouterPropertiesBindingTest {

    @Test
    void camelCaseValuesFromTheChartBind() {
        Map<String, Object> flat = Map.of(
                "meridian.router.registryUrl", "http://tenant-service.ctl",
                "meridian.router.cacheTtl", "45s",
                "meridian.router.jwt.jwkSetUri", "https://idp/certs",
                "meridian.router.jwt.audiences[0]", "meridian-api",
                "meridian.router.health.failureThreshold", "4",
                "meridian.router.health.autoFailover", "true",
                "meridian.router.cellOverrides.aws-euw1-c01", "OFFLINE",
                "meridian.router.maxBodySize", "32MB");
        RouterProperties p = new Binder(new MapConfigurationPropertySource(flat))
                .bind("meridian.router", RouterProperties.class).get();
        assertThat(p.registryUrl()).isEqualTo("http://tenant-service.ctl");
        assertThat(p.cacheTtl()).isEqualTo(Duration.ofSeconds(45));
        assertThat(p.jwt().jwkSetUri()).isEqualTo("https://idp/certs");
        assertThat(p.jwt().audiences()).containsExactly("meridian-api");
        assertThat(p.jwt().tenantClaim()).isEqualTo("tenant_id");
        assertThat(p.health().failureThreshold()).isEqualTo(4);
        assertThat(p.health().successThreshold()).isEqualTo(5);
        assertThat(p.health().autoFailover()).isTrue();
        assertThat(p.health().enabled()).isTrue();          // not switched off by a partial health block
        assertThat(p.cellOverrides()).containsEntry("aws-euw1-c01", "OFFLINE");
        assertThat(p.maxBodySize().toMegabytes()).isEqualTo(32);
        assertThat(new CellDirectory(p)).isNotNull();
    }
}
