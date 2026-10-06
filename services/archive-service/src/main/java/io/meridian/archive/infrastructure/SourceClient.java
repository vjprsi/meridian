package io.meridian.archive.infrastructure;

import io.meridian.web.client.ServiceClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Data-lifecycle commands sent to the service that owns a table (services never read each other's databases).
 * The owning service exposes internal endpoints (paths configurable under {@code meridian.archive.source.*}):
 * <ul>
 *   <li>count — {@code {table_name, created_before, exclude}} -> {@code {count}}</li>
 *   <li>extract — {@code {table_name, created_before, after_id, limit, exclude}} -> {@code {records:[{id, created_at, ...}], skipped_hold}}</li>
 *   <li>confirm — {@code {table_name, archived_object_id, first_record_id, last_record_id, record_count}}: the
 *       batch is safely archived and may leave the online store</li>
 *   <li>purge — {@code {table_name, action, record_selector, exclude}} -> {@code {records_purged}}</li>
 * </ul>
 */
@Component
public class SourceClient {
    private final ServiceClient services;
    private final String countPath;
    private final String extractPath;
    private final String confirmPath;
    private final String purgePath;

    public SourceClient(ServiceClient services,
                        @Value("${meridian.archive.source.count-path:/v1/internal/data-lifecycle/count}") String countPath,
                        @Value("${meridian.archive.source.extract-path:/v1/internal/data-lifecycle/extract}") String extractPath,
                        @Value("${meridian.archive.source.confirm-path:/v1/internal/data-lifecycle/confirm}") String confirmPath,
                        @Value("${meridian.archive.source.purge-path:/v1/internal/data-lifecycle/purge}") String purgePath) {
        this.services = services;
        this.countPath = countPath;
        this.extractPath = extractPath;
        this.confirmPath = confirmPath;
        this.purgePath = purgePath;
    }

    public JsonNode count(String service, JsonNode body, String key) {
        return services.post(service, countPath, body, key);
    }

    public JsonNode extract(String service, JsonNode body, String key) {
        return services.post(service, extractPath, body, key);
    }

    public JsonNode confirm(String service, JsonNode body, String key) {
        return services.post(service, confirmPath, body, key);
    }

    public JsonNode purge(String service, JsonNode body, String key) {
        return services.post(service, purgePath, body, key);
    }
}
