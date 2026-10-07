package io.meridian.mover;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code meridian.mover.*}.
 *
 * @param registryUrl      tenant-service of the control plane (confirm-copy)
 * @param operatorTenant   platform-operator tenant (X-Tenant-Id of registry calls)
 * @param auth             the mover's own client (svc-data-mover, client credentials); empty token URI = dev mode
 * @param cells            per cell code: how to reach its services and object store (else the event's
 *                         {@code endpoints.services_url_template} / {@code endpoints.s3_*})
 * @param skipServices     services not copied (tenant-service: control-plane registry, not cell data)
 * @param services         explicit service list (empty = every service with a database in the manifest)
 * @param pageSize         rows per extract / import call
 * @param rowsPerSecond    copy and verify throttle per mover instance (0 = unlimited)
 * @param objectsPerSecond document copy throttle (0 = unlimited)
 * @param rewrites         column value rewrites applied to copied rows (e.g. document storage URIs to the target bucket)
 * @param lease            how long a mover instance owns a run between heartbeats
 * @param resumeInterval   how often unfinished runs are picked up (crash / restart / transient failure)
 * @param confirm          call /confirm-copy when verified (false: stop at VERIFIED for a dry run)
 * @param events           Kafka listener
 * @param s3Credentials    access key pair used for every cell whose s3 block has none (Helm: one Secret)
 */
@ConfigurationProperties("meridian.mover")
public record MoverProperties(String registryUrl, String operatorTenant, Auth auth, Map<String, Cell> cells,
                              List<String> skipServices, List<String> services, Integer pageSize, Integer rowsPerSecond,
                              Integer objectsPerSecond, List<Rewrite> rewrites, Duration lease, Duration resumeInterval,
                              Boolean confirm, Events events, S3Credentials s3Credentials) {

    public MoverProperties {
        auth = auth == null ? new Auth(null, null, null, null) : auth;
        cells = cells == null ? Map.of() : Map.copyOf(cells);
        skipServices = skipServices == null ? List.of("tenant-service") : List.copyOf(skipServices);
        services = services == null ? List.of() : List.copyOf(services);
        pageSize = pageSize == null || pageSize <= 0 ? 1000 : Math.min(pageSize, 5000);
        rowsPerSecond = rowsPerSecond == null ? 5000 : rowsPerSecond;
        objectsPerSecond = objectsPerSecond == null ? 50 : objectsPerSecond;
        rewrites = rewrites == null ? List.of(new Rewrite("document-service", "document", "storage_uri",
                "s3://{source_bucket}/", "s3://{target_bucket}/")) : List.copyOf(rewrites);
        lease = lease == null ? Duration.ofMinutes(2) : lease;
        resumeInterval = resumeInterval == null ? Duration.ofSeconds(30) : resumeInterval;
        confirm = confirm == null || confirm;
        events = events == null ? new Events(true, "meridian.platform.v1") : events;
        s3Credentials = s3Credentials == null ? new S3Credentials(null, null) : s3Credentials;
    }

    public record Auth(String tokenUri, String clientId, String clientSecret, String scope) {}

    /** How the mover reaches one cell. */
    public record Cell(String servicesUrlTemplate, S3 s3) {}

    public record S3(String endpoint, String bucket, String region, String accessKey, String secretKey) {}

    /** Prefix rewrite of a text column: {@code from} / {@code to} may use {source_bucket} / {target_bucket}. */
    public record Rewrite(String service, String table, String column, String from, String to) {}

    public record Events(boolean enabled, String topic) {}

    public record S3Credentials(String accessKey, String secretKey) {}
}
